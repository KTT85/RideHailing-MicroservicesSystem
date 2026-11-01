package vn.edu.ds03.payment.config;


import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;

@Validated
@ConfigurationProperties("app.payment")
public record PaymentSettings(@Min(1) @Max(20) int maxAttempts,
                              @Min(1) @Max(3600) int retrySeconds,
                              @Min(1024) @Max(1048576) int maxMessageBytes,
                              @jakarta.validation.Valid @NotNull Simulator simulator) {
    public record Simulator(@NotNull Outcome pay, @NotNull Outcome refund,
                            @Min(0) @Max(100) int transientAttempts) { }
    public enum Outcome { SUCCESS, FAILED, TRANSIENT }
    public long backoff(int attempt) {
        return Math.min(3600L, retrySeconds * (1L << Math.min(attempt - 1, 10)));
    }
}
