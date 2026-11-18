package vn.edu.ds03.matching.messaging.consumer;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.edu.ds03.matching.config.MatchingProperties;
import vn.edu.ds03.matching.event.MatchingResultEvent;
import vn.edu.ds03.matching.event.TripRequestedEvent;
import vn.edu.ds03.matching.exception.InvalidTripEventException;
import vn.edu.ds03.matching.messaging.publisher.MatchingEventPublisher;
import vn.edu.ds03.matching.service.MatchingService;

@Component
public class TripRequestedConsumer {

    public static final String RETRY_HEADER = "x-matching-retry-count";
    private static final int MAX_RETRY_HEADER_BOUND = 20;
    private static final Logger log = LoggerFactory.getLogger(TripRequestedConsumer.class);

    private final MatchingService matchingService;
    private final MatchingEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final MatchingProperties properties;

    public TripRequestedConsumer(
            MatchingService matchingService,
            MatchingEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            MatchingProperties properties) {
        this.matchingService = matchingService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @RabbitListener(queues = "${app.matching.commands-queue:matching.commands}")
    public void receive(Message message) {
        byte[] body = message.getBody();
        if (body == null || body.length == 0 || body.length > properties.maxMessageBytes()) {
            throw new AmqpRejectAndDontRequeueException(
                    "TripRequested payload is empty or exceeds size limit");
        }

        TripRequestedEvent event = parseAndValidate(body);
        try {
            Optional<MatchingResultEvent> outcome = matchingService.matchTrip(event);
            if (outcome.isEmpty()) {
                return;
            }
            eventPublisher.publishResult(outcome.get());
            matchingService.markEventCompleted(event.eventId());
        } catch (InvalidTripEventException ex) {
            throw new AmqpRejectAndDontRequeueException("Unprocessable TripRequested event", ex);
        } catch (RuntimeException ex) {
            scheduleRetryOrReject(message, event, ex);
        }
    }

    private TripRequestedEvent parseAndValidate(byte[] body) {
        try {
            TripRequestedEvent parsed = objectMapper.readValue(
                    new String(body, StandardCharsets.UTF_8), TripRequestedEvent.class);
            if (parsed == null) {
                throw new InvalidTripEventException("TripRequested event must not be null");
            }
            parsed.validate();
            return parsed;
        } catch (RuntimeException ex) {
            throw new AmqpRejectAndDontRequeueException("Malformed TripRequested payload", ex);
        }
    }

    private void scheduleRetryOrReject(
            Message message, TripRequestedEvent event, RuntimeException failure) {
        int currentAttempt = extractRetryCount(message);
        if (currentAttempt >= properties.maxRetryAttempts()) {
            throw new AmqpRejectAndDontRequeueException("Matching consumer retries exhausted", failure);
        }

        Message retryMessage = MessageBuilder.fromClonedMessage(message)
                .setHeader(RETRY_HEADER, currentAttempt + 1)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        try {
            eventPublisher.sendRetry(retryMessage);
        } catch (RuntimeException publishFailure) {
            throw new ImmediateRequeueAmqpException(
                    "Unable to publish retry message to matching.retry", publishFailure);
        }

        log.warn(
                "service=matching-service event=ConsumerRetry correlationId={} eventId={} attempt={}",
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
}
