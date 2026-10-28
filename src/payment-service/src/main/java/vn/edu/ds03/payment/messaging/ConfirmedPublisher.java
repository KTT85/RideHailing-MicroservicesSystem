package vn.edu.ds03.payment.messaging;


import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class ConfirmedPublisher {
    private final RabbitTemplate rabbit;
    public ConfirmedPublisher(RabbitTemplate rabbit) { this.rabbit = rabbit; }

    public void send(String exchange, String routingKey, Message message) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbit.send(exchange, routingKey, message, correlation);
            var confirmation = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirmation.ack() || correlation.getReturned() != null)
                throw new IllegalStateException("Broker rejected or could not route message");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publisher interrupted", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Publish was not confirmed and routed", ex);
        }
    }
}
