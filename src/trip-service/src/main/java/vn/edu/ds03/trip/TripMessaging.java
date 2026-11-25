package vn.edu.ds03.trip;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableScheduling
class TripBroker {
    static final String EXCHANGE = "ride.events";
    static final String QUEUE = "trip-service.queue";
    static final int[] RETRY_SECONDS = {5, 15, 60};

    static String retryQueue(int index) {
        return "trip-service.retry." + RETRY_SECONDS[index] + "s";
    }

    @Bean
    Declarables tripTopology() {
        var events = new TopicExchange(EXCHANGE);
        var deadLetters = new DirectExchange("ridehailing.dlx");
        var deadQueue = QueueBuilder.durable("trip-service.dlq").build();
        var incoming = QueueBuilder.durable(QUEUE).deadLetterExchange(deadLetters.getName())
                .deadLetterRoutingKey(deadQueue.getName()).build();
        List<Declarable> declarations = new ArrayList<>(List.of(events, deadLetters, deadQueue, incoming,
                BindingBuilder.bind(deadQueue).to(deadLetters).with(deadQueue.getName())));
        for (String type : List.of("DriverAssigned", "NoDriverAvailable", "DriverStarted", "DriverReleased",
                "PaymentSucceeded", "PaymentFailed", "PaymentRefunded", "PaymentRefundFailed", "PaymentReviewRequired")) {
            declarations.add(BindingBuilder.bind(incoming).to(events).with(type));
        }
        for (int i = 0; i < RETRY_SECONDS.length; i++) {
            declarations.add(QueueBuilder.durable(retryQueue(i)).ttl(RETRY_SECONDS[i] * 1000)
                    .deadLetterExchange("").deadLetterRoutingKey(QUEUE).build());
        }
        return new Declarables(declarations);
    }
}

@Component
class TripMessaging {
    private static final Logger log = LoggerFactory.getLogger(TripMessaging.class);
    private final TripOutbox outbox;
    private final RideWorkflow workflow;
    private final TripFailpoint failpoint;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;

    TripMessaging(TripOutbox outbox, RideWorkflow workflow, TripFailpoint failpoint, RabbitTemplate rabbit, ObjectMapper json) {
        this.outbox = outbox;
        this.workflow = workflow;
        this.failpoint = failpoint;
        this.rabbit = rabbit;
        this.json = json;
    }

    @RabbitListener(queues = TripBroker.QUEUE)
    void receive(Message message) {
        try {
            workflow.receive(json.readTree(message.getBody()));
        } catch (RuntimeException error) {
            Object header = message.getMessageProperties().getHeader("x-retry-count");
            int count = header instanceof Number number ? number.intValue() : 0;
            log.warn("Trip event failed, retryCount={}", count, error);
            if (count < 0 || count >= TripBroker.RETRY_SECONDS.length) {
                throw new AmqpRejectAndDontRequeueException("Trip retries exhausted", error);
            }
            var retry = MessageBuilder.fromMessage(message).setExpiration(null)
                    .setHeader("x-retry-count", count + 1).build();
            publish("", TripBroker.retryQueue(count), retry, true);
        }
    }

    @Scheduled(fixedDelay = 1000)
    void publishOutbox() {
        for (var event : outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAscIdAsc()) {
            try {
                if ("PaymentRefundRequested".equals(event.eventType) && failpoint.mark(event.aggregateId)) {
                    log.warn("event=F02_BEFORE_REFUND_PUBLISH tripId={} correlationId={}", event.aggregateId, event.correlationId);
                    System.exit(42);
                }
                publish(TripBroker.EXCHANGE, event.eventType, outboxMessage(event), true);
                event.publishedAt = Instant.now();
                outbox.save(event);
            } catch (RuntimeException error) {
                log.warn("Trip outbox publish failed eventId={} correlationId={}", event.id, event.correlationId, error);
                break;
            }
        }
    }

    Message outboxMessage(TripOutboxEvent event) {
        String timestamp = event.createdAt.toString();
        var body = json.createObjectNode().put("eventId", event.id).put("eventType", event.eventType)
                .put("correlationId", event.correlationId).put("source", "trip-service")
                .put("occurredAt", timestamp).put("timestamp", timestamp);
        body.set("payload", event.payload);
        return MessageBuilder.withBody(json.writeValueAsBytes(body))
                .setMessageId(event.id).setCorrelationId(event.correlationId).setType(event.eventType)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
    }

    private void publish(String exchange, String routingKey, Message message, boolean requireRoute) {
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbit.send(exchange, routingKey, message, correlation);
            if (!correlation.getFuture().get(5, TimeUnit.SECONDS).ack() || requireRoute && correlation.getReturned() != null) {
                throw new IllegalStateException("Broker did not confirm or route Trip message");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during RabbitMQ confirmation", e);
        } catch (Exception e) {
            throw new IllegalStateException("Trip RabbitMQ confirmation failed", e);
        }
    }
}

@Component
class TripFailpoint {
    private final TripStore trips;
    private final boolean enabled;

    TripFailpoint(TripStore trips, @Value("${DS03_ENABLE_DEMO_FAILPOINTS:false}") boolean enabled,
            @Value("${DS03_DEMO_FAILPOINT_TOKEN:}") String token) {
        this.trips = trips;
        this.enabled = enabled && token.length() >= 32;
    }

    @Transactional
    public boolean mark(String id) {
        if (!enabled) return false;
        trips.lockWorkflow();
        Ride ride = trips.findById(id).orElse(null);
        if (ride == null || !"F02".equals(ride.demoFault) || ride.demoFaultAt != null
                || !ride.driverReleased || !"REFUND_PENDING".equals(ride.paymentState)) return false;
        ride.demoFaultAt = Instant.now();
        return true;
    }
}
