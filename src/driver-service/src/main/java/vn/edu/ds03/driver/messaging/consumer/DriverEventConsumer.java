package vn.edu.ds03.driver.messaging.consumer;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.edu.ds03.driver.config.DriverMessagingProperties;
import vn.edu.ds03.driver.event.DriverInboundEvent;
import vn.edu.ds03.driver.exception.InvalidDriverEventException;
import vn.edu.ds03.driver.messaging.publisher.ConfirmedRabbitPublisher;
import vn.edu.ds03.driver.messaging.publisher.DriverOutboxPublisher;
import vn.edu.ds03.driver.service.DriverEventService;

@Component
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class DriverEventConsumer {

    public static final String RETRY_HEADER = "x-driver-retry-count";
    private static final int MAX_RETRY_HEADER_BOUND = 20;
    private static final Logger log = LoggerFactory.getLogger(DriverEventConsumer.class);

    private final DriverEventService eventService;
    private final ObjectMapper objectMapper;
    private final ConfirmedRabbitPublisher rabbitPublisher;
    private final DriverOutboxPublisher outboxPublisher;
    private final DriverMessagingProperties properties;

    public DriverEventConsumer(
            DriverEventService eventService,
            ObjectMapper objectMapper,
            ConfirmedRabbitPublisher rabbitPublisher,
            DriverOutboxPublisher outboxPublisher,
            DriverMessagingProperties properties) {
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.rabbitPublisher = rabbitPublisher;
        this.outboxPublisher = outboxPublisher;
        this.properties = properties;
    }

    @RabbitListener(queues = "${app.messaging.commands-queue:driver.commands}")
    public void receive(Message message) {
        byte[] body = message.getBody();
        if (body == null || body.length == 0 || body.length > properties.maxMessageBytes()) {
            throw new AmqpRejectAndDontRequeueException("Driver event payload empty or exceeds size limit");
        }

        DriverInboundEvent event = parseAndValidate(body);
        boolean queuedOutbox;
        try {
            queuedOutbox = eventService.processEvent(event);
        } catch (InvalidDriverEventException ex) {
            throw new AmqpRejectAndDontRequeueException("Unprocessable driver domain event", ex);
        } catch (DataIntegrityViolationException ex) {
            if (isConcurrentDuplicate(event.eventId())) {
                log.debug(
                        "service=driver-service event=ConcurrentDuplicateAcked eventId={}",
                        event.eventId());
                return;
            }
            scheduleRetryOrReject(message, event, ex);
            return;
        } catch (RuntimeException ex) {
            scheduleRetryOrReject(message, event, ex);
            return;
        }

        if (queuedOutbox) {
            flushOutboxSafely(event.correlationId());
        }
    }

    private DriverInboundEvent parseAndValidate(byte[] body) {
        try {
            DriverInboundEvent parsed = objectMapper.readValue(
                    new String(body, StandardCharsets.UTF_8), DriverInboundEvent.class);
            if (parsed == null) {
                throw new InvalidDriverEventException("Driver event must not be null");
            }
            parsed.validate();
            return parsed;
        } catch (RuntimeException ex) {
            throw new AmqpRejectAndDontRequeueException("Invalid driver event payload", ex);
        }
    }

    private boolean isConcurrentDuplicate(String eventId) {
        try {
            return eventService.isAlreadyProcessed(eventId);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void scheduleRetryOrReject(
            Message message, DriverInboundEvent event, RuntimeException failure) {
        int currentAttempt = extractRetryCount(message);
        if (currentAttempt >= properties.maxRetryAttempts()) {
            throw new AmqpRejectAndDontRequeueException("Driver event consumer retries exhausted", failure);
        }

        Message retryMessage = MessageBuilder.fromClonedMessage(message)
                .setHeader(RETRY_HEADER, currentAttempt + 1)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        try {
            rabbitPublisher.send(
                    properties.deliveryExchange(),
                    properties.retryQueue(),
                    retryMessage,
                    true);
        } catch (RuntimeException publishFailure) {
            throw new ImmediateRequeueAmqpException(
                    "Unable to publish retry message to driver.retry", publishFailure);
        }

        log.warn(
                "service=driver-service event=ConsumerRetry correlationId={} eventId={} attempt={}",
                event.correlationId(),
                event.eventId(),
                currentAttempt + 1);
    }

    private int extractRetryCount(Message message) {
        Object header = message.getMessageProperties().getHeaders().get(RETRY_HEADER);
        if (header == null) {
            return 0;
        }
        boolean integralNumber = header instanceof Byte
                || header instanceof Short
                || header instanceof Integer
                || header instanceof Long;
        if (!integralNumber) {
            throw new AmqpRejectAndDontRequeueException("Invalid retry count header");
        }
        long count = ((Number) header).longValue();
        if (count < 0 || count > MAX_RETRY_HEADER_BOUND) {
            throw new AmqpRejectAndDontRequeueException("Retry count header out of bounds");
        }
        return (int) count;
    }

    private void flushOutboxSafely(String correlationId) {
        try {
            outboxPublisher.publishDueEvents();
        } catch (RuntimeException ex) {
            log.warn(
                    "service=driver-service event=ImmediateOutboxFlushDeferred correlationId={}",
                    correlationId);
        }
    }
}
