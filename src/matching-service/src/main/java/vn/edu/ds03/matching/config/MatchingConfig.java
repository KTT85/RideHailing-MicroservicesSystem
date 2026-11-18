package vn.edu.ds03.matching.config;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(MatchingProperties.class)
public class MatchingConfig {

    private static final List<String> INBOUND_ROUTING_KEYS = List.of("TripRequested", "trip.requested");

    @Bean
    public Clock matchingClock() {
        return Clock.systemUTC();
    }

    @Bean
    public RestClient driverRestClient(MatchingProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(properties.connectTimeoutMs(), 250)));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(properties.readTimeoutMs(), 250)));
        return RestClient.builder()
                .baseUrl(properties.driverServiceUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    public Declarables matchingMessagingTopology(MatchingProperties properties) {
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

        for (String routingKey : INBOUND_ROUTING_KEYS) {
            declarables.add(BindingBuilder.bind(commandsQueue).to(eventsExchange).with(routingKey));
        }
        return new Declarables(declarables);
    }
}
