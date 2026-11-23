package vn.edu.ds03.payment.controller;

import vn.edu.ds03.payment.service.PaymentQueries;
import vn.edu.ds03.payment.dto.PaymentView;

import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PaymentController.class);
    private final PaymentQueries queries;
    public PaymentController(PaymentQueries queries) { this.queries = queries; }

    @GetMapping("/payments/by-trip/{tripId}")
    PaymentView get(@PathVariable String tripId, Authentication auth) {
        Jwt jwt = (Jwt) auth.getPrincipal();
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return queries.byTrip(tripId, jwt.getSubject(), admin);
    }
    @GetMapping("/internal/payments/by-trip/{tripId}")
    PaymentView internal(@PathVariable String tripId) {
        return queries.byTrip(tripId, null, true);
    }
    @PostMapping("/admin/payments/operations/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void retryOperation(@PathVariable String id, Authentication auth) {
        queries.retryOperation(id);
        log.info("service=payment-service event=AdminOperationRetry actor={} operationId={} result=accepted", auth.getName(), id);
    }

    @PostMapping("/admin/payments/outbox/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void retryOutbox(@PathVariable String id, Authentication auth) {
        queries.retryOutbox(id);
        log.info("service=payment-service event=AdminOutboxRetry actor={} eventId={} result=accepted", auth.getName(), id);
    }

}
