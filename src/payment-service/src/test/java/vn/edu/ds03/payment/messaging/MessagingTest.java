package vn.edu.ds03.payment.messaging;

import vn.edu.ds03.payment.messaging.ConfirmedPublisher;
import vn.edu.ds03.payment.exception.InvalidCommand;
import vn.edu.ds03.payment.dto.PaymentEvent;
import vn.edu.ds03.payment.messaging.PaymentListener;
import vn.edu.ds03.payment.service.PaymentService;
import vn.edu.ds03.payment.config.PaymentSettings;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.amqp.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;

class MessagingTest {
    final PaymentService service = mock(PaymentService.class);
    final ConfirmedPublisher publisher = mock(ConfirmedPublisher.class);
    final JsonMapper json = JsonMapper.builder().build();
    final PaymentSettings settings = new PaymentSettings(3, 1, 65536,
            new PaymentSettings.Simulator(PaymentSettings.Outcome.SUCCESS, PaymentSettings.Outcome.SUCCESS, 0));
    final PaymentListener listener = new PaymentListener(service, json, publisher, settings);

    Message valid() {
        String trip = UUID.randomUUID().toString();
        var event = new PaymentEvent(UUID.randomUUID().toString(), "PaymentRequested", UUID.randomUUID().toString(),
                Instant.now(), new PaymentEvent.Payload(trip, UUID.randomUUID().toString(), "TRANSFER",
                new BigDecimal("50000"), "VND", trip + ":PAY"));
        return MessageBuilder.withBody(json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8)).build();
    }
    @Test void validMessageCommitsWithoutPublishingRetry() {
        listener.receive(valid()); verify(service).accept(any()); verifyNoInteractions(publisher);
    }
    @Test void malformedAndOversizedMessagesGoToDlq() {
        assertThatThrownBy(() -> listener.receive(new Message("not-json".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.receive(new Message(new byte[65537])))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(service, publisher);
    }
    @Test void databaseFailurePublishesPersistentRetryBeforeAck() {
        doThrow(new IllegalStateException("DB unavailable")).when(service).accept(any());
        listener.receive(valid());
        verify(publisher).send(eq("payment.delivery"), eq("payment.retry"), argThat(m ->
                Integer.valueOf(1).equals(m.getMessageProperties().getHeader("payment-retry-count"))
                && m.getMessageProperties().getDeliveryMode() == MessageDeliveryMode.PERSISTENT));
    }
    @Test void exhaustedConsumerRetriesRejectToDlq() {
        doThrow(new IllegalStateException("DB unavailable")).when(service).accept(any());
        Message message = valid(); message.getMessageProperties().setHeader("payment-retry-count", 3);
        assertThatThrownBy(() -> listener.receive(message)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(publisher);
    }
    @Test void retryPublishFailurePreservesOriginalDelivery() {
        doThrow(new IllegalStateException("DB unavailable")).when(service).accept(any());
        doThrow(new IllegalStateException("Broker unavailable")).when(publisher).send(anyString(), anyString(), any());
        assertThatThrownBy(() -> listener.receive(valid())).isInstanceOf(ImmediateRequeueAmqpException.class);
    }
    @Test void invalidRetryCounterCannotBypassBound() {
        doThrow(new IllegalStateException("DB unavailable")).when(service).accept(any());
        Message message = valid(); message.getMessageProperties().setHeader("payment-retry-count", -100);
        assertThatThrownBy(() -> listener.receive(message)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(publisher);
    }
    @Test void immutableConflictRejectsWithoutRetry() {
        doThrow(new InvalidCommand("amount conflict")).when(service).accept(any());
        assertThatThrownBy(() -> listener.receive(valid())).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(publisher);
    }
    @Test void publisherRequiresBothConfirmationAndRoute() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        var confirmed = new ConfirmedPublisher(rabbit);
        doAnswer(inv -> {
            CorrelationData data = inv.getArgument(3);
            data.getFuture().complete(new CorrelationData.Confirm(true, null)); return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThatCode(() -> confirmed.send("ride.events", "PaymentSucceeded", valid())).doesNotThrowAnyException();
        doAnswer(inv -> {
            CorrelationData data = inv.getArgument(3);
            data.setReturned(new ReturnedMessage(valid(), 312, "NO_ROUTE", "ride.events", "PaymentSucceeded"));
            data.getFuture().complete(new CorrelationData.Confirm(true, null)); return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThatThrownBy(() -> confirmed.send("ride.events", "PaymentSucceeded", valid()))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void invalidMethodKeyAndAmountAreRejected() {
        String trip = UUID.randomUUID().toString();
        var event = new PaymentEvent(UUID.randomUUID().toString(), "PaymentRequested", UUID.randomUUID().toString(),
                Instant.now(), new PaymentEvent.Payload(trip, UUID.randomUUID().toString(), "CASH",
                new BigDecimal("50000"), "VND", trip + ":PAY"));
        assertThatThrownBy(event::validate).isInstanceOf(InvalidCommand.class);
        var negative = new PaymentEvent(event.eventId(), event.eventType(), event.correlationId(), Instant.now(),
                new PaymentEvent.Payload(trip, event.payload().passengerId(), "TRANSFER", new BigDecimal("-1"), "VND", "wrong"));
        assertThatThrownBy(negative::validate).isInstanceOf(InvalidCommand.class);
    }
}
