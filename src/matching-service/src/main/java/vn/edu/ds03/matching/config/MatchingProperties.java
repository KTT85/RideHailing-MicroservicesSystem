package vn.edu.ds03.matching.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.matching")
public record MatchingProperties(
        @DefaultValue("http://localhost:8082") String driverServiceUrl,
        @DefaultValue("") String driverInternalToken,
        @DefaultValue("5") int candidateLimit,
        @DefaultValue("2000") int connectTimeoutMs,
        @DefaultValue("3000") int readTimeoutMs,
        @DefaultValue("ride.events") String eventsExchange,
        @DefaultValue("matching.delivery") String deliveryExchange,
        @DefaultValue("matching.commands") String commandsQueue,
        @DefaultValue("matching.retry") String retryQueue,
        @DefaultValue("matching.dlq") String dlqQueue,
        @DefaultValue("5000") int retryTtlMs,
        @DefaultValue("3") int maxRetryAttempts,
        @DefaultValue("65536") int maxMessageBytes) {
}
