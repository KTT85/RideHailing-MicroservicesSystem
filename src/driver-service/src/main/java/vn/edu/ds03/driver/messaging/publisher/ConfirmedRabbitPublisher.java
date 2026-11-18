package vn.edu.ds03.driver.messaging.publisher;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class ConfirmedRabbitPublisher {

    private static final long CONFIRM_TIMEOUT_SECONDS = 5L;

    private final ObjectProvider<RabbitTemplate> rabbitTemplateProvider;
    private final RabbitTemplate directTemplate;

    @Autowired
    public ConfirmedRabbitPublisher(ObjectProvider<RabbitTemplate> rabbitTemplateProvider) {
        this.rabbitTemplateProvider = Objects.requireNonNull(rabbitTemplateProvider);
        this.directTemplate = null;
    }

    public ConfirmedRabbitPublisher(RabbitTemplate rabbitTemplate) {
        this.directTemplate = Objects.requireNonNull(rabbitTemplate);
        this.directTemplate.setMandatory(true);
        this.rabbitTemplateProvider = null;
    }

    public void send(String exchange, String routingKey, Message message, boolean requireRoute) {
        RabbitTemplate template = resolveTemplate();
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            template.send(exchange, routingKey, message, correlation);
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (confirm == null || !confirm.ack()) {
                throw new IllegalStateException("Broker did not acknowledge published message");
            }
            if (requireRoute && correlation.getReturned() != null) {
                throw new IllegalStateException("Broker could not route mandatory message");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting broker publish confirmation", ex);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to publish and confirm message with broker", ex);
        }
    }

    private RabbitTemplate resolveTemplate() {
        if (directTemplate != null) {
            return directTemplate;
        }
        RabbitTemplate provided = rabbitTemplateProvider.getIfAvailable();
        if (provided == null) {
            throw new IllegalStateException("RabbitTemplate is not configured");
        }
        provided.setMandatory(true);
        return provided;
    }
}
