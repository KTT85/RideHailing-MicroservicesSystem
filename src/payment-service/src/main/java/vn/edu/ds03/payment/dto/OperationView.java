package vn.edu.ds03.payment.dto;


import java.time.Instant;

public record OperationView(String id, String type, String status, int attemptCount,
                            Instant nextRetryAt, String lastError) { }
