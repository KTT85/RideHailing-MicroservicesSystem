package vn.edu.ds03.payment.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_operations")
public class PaymentOperation {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "payment_id") private String paymentId;
    @Column(name = "operation_type") private String operationType;
    @Column(name = "idempotency_key") private String idempotencyKey;
    private BigDecimal amount;
    private String status;
    @Column(name = "attempt_count") private int attemptCount;
    @Column(name = "next_retry_at") private LocalDateTime nextRetryAt;
    @Column(name = "last_error") private String lastError;
    @Column(name = "correlation_id") private String correlationId;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;
    public PaymentOperation() { }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(LocalDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
