package vn.edu.ds03.matching.messaging.publisher;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.edu.ds03.matching.config.MatchingProperties;
import vn.edu.ds03.matching.event.MatchingResultEvent;

@Component
public class MatchingEventPublisher {

    private static final long CONFIRM_TIMEOUT_SECONDS = 5L;

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final MatchingProperties properties;

    public MatchingEventPublisher(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            MatchingProperties properties) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate);
        this.rabbitTemplate.setMandatory(true);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.properties = Objects.requireNonNull(properties);
    }

    public void publishResult(MatchingResultEvent event) {
        byte[] body = objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        Message message = MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(event.eventId())
                .setCorrelationId(event.correlationId())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        sendWithConfirm(properties.eventsExchange(), event.eventType(), message, false);
    }

    public void sendRetry(Message retryMessage) {
        sendWithConfirm(
                properties.deliveryExchange(),
                properties.retryQueue(),
                retryMessage,
                true);
    }

    private void sendWithConfirm(
            String exchange, String routingKey, Message message, boolean requireRoute) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbitTemplate.send(exchange, routingKey, message, correlation);
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (confirm == null || !confirm.ack()) {
                throw new IllegalStateException("Broker did not acknowledge published message");
            }
            if (requireRoute && correlation.getReturned() != null) {
                throw new IllegalStateException("Broker could not route mandatory retry message");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted awaiting broker publish confirmation", ex);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to publish message to RabbitMQ", ex);
        }
    }
}
