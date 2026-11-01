package vn.edu.ds03.payment.dto;


import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PaymentView(String id, String tripId, String passengerId, String method,
                          BigDecimal amount, String currency, String status, BigDecimal refundedAmount,
                          Instant paidAt, Instant refundedAt, List<OperationView> operations) { }
