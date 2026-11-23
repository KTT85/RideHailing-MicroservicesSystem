package vn.edu.ds03.trip.payment;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class PaymentOutboxWorker {
    private final JdbcTemplate db;
    private final RabbitTemplate rabbit;

    PaymentOutboxWorker(JdbcTemplate db, RabbitTemplate rabbit) {
        this.db = db;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${trip.payment.publish-delay-ms:1000}")
    void publishPending() throws Exception {
        var rows = db.queryForList("""
            SELECT event_id,event_type,body FROM trip_payment_outbox WHERE published=FALSE ORDER BY seq LIMIT 50
            """);
        for (var row : rows) {
            String id = (String) row.get("event_id");
            String type = (String) row.get("event_type");
            var message = MessageBuilder.withBody(((String) row.get("body")).getBytes(StandardCharsets.UTF_8))
                    .setContentType("application/json").setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .setType(type).setMessageId(id).build();
            var confirm = new CorrelationData(UUID.randomUUID().toString());
            rabbit.send(PaymentMessaging.EXCHANGE, type, message, confirm);
            if (!confirm.getFuture().get(10, TimeUnit.SECONDS).ack() || confirm.getReturned() != null)
                throw new IllegalStateException("Payment event not routed: " + id);
            db.update("UPDATE trip_payment_outbox SET published=TRUE WHERE event_id=?", id);
        }
    }
}
