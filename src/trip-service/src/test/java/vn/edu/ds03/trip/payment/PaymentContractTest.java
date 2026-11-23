package vn.edu.ds03.trip.payment;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.ds03.trip.payment.PaymentContract.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PaymentContractTest {
    private final UUID trip = UUID.randomUUID();
    private final UUID passenger = UUID.randomUUID();
    private final UUID correlation = UUID.randomUUID();

    @Test
    void sendsExactlyTheAgreedEnvelopeAndPayloadForEachOperation() {
        for (Method method : Method.values()) {
            var terms = new Terms(trip, passenger, method, new BigDecimal("50000.50"));
            for (Command type : Command.values()) {
                if ((method == Method.CASH && (type == Command.PaymentRequested || type == Command.PaymentRefundRequested))
                        || (method == Method.TRANSFER && type == Command.CashPaymentConfirmationRequested)) {
                    assertThrows(IllegalArgumentException.class, () -> command(type, terms, correlation));
                    continue;
                }
                var mapper = JsonMapper.builder().build();
                var json = mapper.readTree(mapper.writeValueAsString(command(type, terms, correlation)));
                assertEquals(5, json.size());
                assertEquals(type.name(), json.get("eventType").stringValue());
                UUID.fromString(json.get("eventId").stringValue());
                assertEquals(correlation.toString(), json.get("correlationId").stringValue());
                assertTrue(json.get("timestamp").stringValue().endsWith("Z"));
                var payload = json.get("payload");
                assertEquals(trip.toString(), payload.get("tripId").stringValue());
                assertEquals(passenger.toString(), payload.get("passengerId").stringValue());
                assertEquals(method.name(), payload.get("method").stringValue());
                assertEquals("VND", payload.get("currency").stringValue());
                assertEquals(0, terms.amount().compareTo(payload.get("amount").decimalValue()));
                switch (type) {
                    case PaymentRequested -> assertEquals(trip + ":PAY", payload.get("idempotencyKey").stringValue());
                    case PaymentRefundRequested -> assertEquals(trip + ":REFUND", payload.get("idempotencyKey").stringValue());
                    case CashPaymentConfirmationRequested -> assertEquals(trip + ":CONFIRM_CASH", payload.get("idempotencyKey").stringValue());
                    case TripCompleted -> assertFalse(payload.has("idempotencyKey"));
                }
            }
        }
    }

    @Test
    void rejectsZeroNegativeAndFractionalSubunits() {
        for (String amount : new String[]{"0", "-1", "12.345"})
            assertThrows(IllegalArgumentException.class, () -> new Terms(trip, passenger, Method.CASH, new BigDecimal(amount)));
    }
}
