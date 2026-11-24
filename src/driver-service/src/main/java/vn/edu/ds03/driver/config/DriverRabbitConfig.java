package vn.edu.ds03.driver.config;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(DriverMessagingProperties.class)
public class DriverRabbitConfig {

    private static final List<String> INBOUND_EVENT_ROUTING_KEYS = List.of(
            "DriverReleaseRequested",
            "DriverRejected",
            "DriverStartRequested",
            "TripStarted",
            "TripCompleted",
            "TripCancelled",
            "driver.release_requested",
            "trip.rejected",
            "trip.started",
            "trip.completed",
            "trip.cancelled");

    @Bean
    public Clock driverClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true", matchIfMissing = true)
    public Declarables driverMessagingTopology(DriverMessagingProperties properties) {
        TopicExchange eventsExchange = new TopicExchange(properties.eventsExchange(), true, false);
        DirectExchange deliveryExchange = new DirectExchange(properties.deliveryExchange(), true, false);

        Queue commandsQueue = QueueBuilder.durable(properties.commandsQueue())
                .deadLetterExchange(properties.deliveryExchange())
                .deadLetterRoutingKey(properties.dlqQueue())
                .build();
        Queue retryQueue = QueueBuilder.durable(properties.retryQueue())
                .ttl(properties.retryTtlMs())
                .deadLetterExchange(properties.deliveryExchange())
                .deadLetterRoutingKey(properties.commandsQueue())
                .build();
        Queue dlqQueue = QueueBuilder.durable(properties.dlqQueue()).build();

        List<Declarable> declarables = new ArrayList<>(List.of(
                eventsExchange,
                deliveryExchange,
                commandsQueue,
                retryQueue,
                dlqQueue,
                BindingBuilder.bind(commandsQueue).to(deliveryExchange).with(properties.commandsQueue()),
                BindingBuilder.bind(retryQueue).to(deliveryExchange).with(properties.retryQueue()),
                BindingBuilder.bind(dlqQueue).to(deliveryExchange).with(properties.dlqQueue())));

        for (String routingKey : INBOUND_EVENT_ROUTING_KEYS) {
            declarables.add(BindingBuilder.bind(commandsQueue).to(eventsExchange).with(routingKey));
        }
        return new Declarables(declarables);
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(
            name = {"app.messaging.enabled", "app.messaging.outbox-scheduler-enabled"},
            havingValue = "true",
            matchIfMissing = true)
    static class MessagingSchedulingConfig {
    }
}
