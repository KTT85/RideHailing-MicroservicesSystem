package vn.edu.ds03.driver.config;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.messaging")
public record DriverMessagingProperties(
        @DefaultValue("ride.events") String eventsExchange,
        @DefaultValue("driver.delivery") String deliveryExchange,
        @DefaultValue("driver.commands") String commandsQueue,
        @DefaultValue("driver.retry") String retryQueue,
        @DefaultValue("driver.dlq") String dlqQueue,
        @DefaultValue("5000") int retryTtlMs,
        @DefaultValue("3") int maxRetryAttempts,
        @DefaultValue("65536") int maxMessageBytes,
        @DefaultValue("2000") long outboxDelayMs,
        @DefaultValue("5") long outboxRetrySeconds,
        @DefaultValue("0.2") double outboxJitterFactor,
        @DefaultValue("true") boolean outboxSchedulerEnabled) {

    private static final long MAX_BACKOFF_SECONDS = 3600L;

    public long computeBackoffSeconds(int attempt) {
        int shift = Math.min(Math.max(attempt - 1, 0), 12);
        long base = Math.min(Math.max(outboxRetrySeconds, 1L), MAX_BACKOFF_SECONDS);
        long deterministic = Math.min(base * (1L << shift), MAX_BACKOFF_SECONDS);
        double clampedJitter = Math.min(Math.max(outboxJitterFactor, 0.0), 1.0);
        if (clampedJitter == 0.0) {
            return deterministic;
        }
        long maxExtra = Math.round(deterministic * clampedJitter);
        if (maxExtra <= 0L) {
            return deterministic;
        }
        long jitterSeconds = ThreadLocalRandom.current().nextLong(0L, maxExtra + 1L);
        return Math.min(deterministic + jitterSeconds, MAX_BACKOFF_SECONDS);
    }
}
