package vn.edu.ds03.matching.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.edu.ds03.matching.config.MatchingConfig;
import vn.edu.ds03.matching.config.MatchingProperties;
import vn.edu.ds03.matching.event.MatchingResultEvent;
import vn.edu.ds03.matching.event.TripRequestedEvent;
import vn.edu.ds03.matching.exception.InvalidTripEventException;
import vn.edu.ds03.matching.messaging.consumer.TripRequestedConsumer;
import vn.edu.ds03.matching.messaging.publisher.MatchingEventPublisher;
import vn.edu.ds03.matching.service.MatchingService;

class TripRequestedConsumerTest {

    private final MatchingService matchingService = mock(MatchingService.class);
    private final MatchingEventPublisher eventPublisher = mock(MatchingEventPublisher.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final MatchingProperties properties = new MatchingProperties(
            "http://driver-service:8080",
            "driver-internal-service-token-32-chars",
            5,
            2000,
            3000,
            "ride.events",
            "matching.delivery",
            "matching.commands",
            "matching.retry",
            "matching.dlq",
            5000,
            3,
            65536);
    private final TripRequestedConsumer consumer = new TripRequestedConsumer(
            matchingService, eventPublisher, jsonMapper, properties);

    private Message validTripRequestedMessage(String eventId) {
        String tripId = UUID.randomUUID().toString();
        var event = new TripRequestedEvent(
                eventId,
                "TripRequested",
                UUID.randomUUID().toString(),
                Instant.parse("2026-11-18T07:10:00Z"),
                null,
                new TripRequestedEvent.Payload(
                        tripId,
                        null,
                        UUID.randomUUID().toString(),
                        new BigDecimal("10.7768890"),
                        new BigDecimal("106.7008060"),
                        "BIKE",
                        null));
        return MessageBuilder.withBody(
                        jsonMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8))
                .build();
    }

    @Test
    @DisplayName("valid TripRequested matches driver, publishes result, and marks event completed")
    void receive_validEvent_publishesResultAndMarksCompleted() {
        String eventId = UUID.randomUUID().toString();
        var result = new MatchingResultEvent(
                UUID.randomUUID().toString(),
                "DriverAssigned",
                UUID.randomUUID().toString(),
                Instant.parse("2026-11-18T07:10:01Z"),
                new MatchingResultEvent.Payload(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        "BIKE",
                        "59X1-111.11",
                        Instant.parse("2026-11-18T07:10:01Z"),
                        null));
        when(matchingService.matchTrip(any())).thenReturn(Optional.of(result));

        consumer.receive(validTripRequestedMessage(eventId));

        verify(eventPublisher).publishResult(result);
        verify(matchingService).markEventCompleted(eventId);
    }

    @Test
    @DisplayName("duplicate already-completed event ACKs without publishing")
    void receive_duplicateCompletedEvent_skipsPublish() {
        String eventId = UUID.randomUUID().toString();
        when(matchingService.matchTrip(any())).thenReturn(Optional.empty());

        consumer.receive(validTripRequestedMessage(eventId));

        verifyNoInteractions(eventPublisher);
        verify(matchingService, never()).markEventCompleted(anyString());
    }

    @Test
    @DisplayName("malformed JSON, oversized payload, and InvalidTripEventException reject to DLQ")
    void receive_invalidPayloadOrDomainError_rejectsToDlq() {
        assertThatThrownBy(() -> consumer.receive(
                new Message("bad-json".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThatThrownBy(() -> consumer.receive(new Message(new byte[65537])))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        doThrow(new InvalidTripEventException("bad vehicle"))
                .when(matchingService).matchTrip(any());
        assertThatThrownBy(() -> consumer.receive(validTripRequestedMessage(UUID.randomUUID().toString())))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("transient upstream failure sends persistent retry and rejects to DLQ after exhaustion")
    void receive_transientFailure_retriesThenExhaustsToDlq() {
        doThrow(new IllegalStateException("driver-service 503"))
                .when(matchingService).matchTrip(any());

        consumer.receive(validTripRequestedMessage(UUID.randomUUID().toString()));
        verify(eventPublisher).sendRetry(argThat(msg ->
                Integer.valueOf(1).equals(
                        msg.getMessageProperties().getHeader(TripRequestedConsumer.RETRY_HEADER))
                        && msg.getMessageProperties().getDeliveryMode() == MessageDeliveryMode.PERSISTENT));

        Message exhausted = validTripRequestedMessage(UUID.randomUUID().toString());
        exhausted.getMessageProperties().setHeader(TripRequestedConsumer.RETRY_HEADER, 3);
        assertThatThrownBy(() -> consumer.receive(exhausted))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        Message tampered = validTripRequestedMessage(UUID.randomUUID().toString());
        tampered.getMessageProperties().setHeader(TripRequestedConsumer.RETRY_HEADER, Double.NaN);
        assertThatThrownBy(() -> consumer.receive(tampered))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    @DisplayName("retry publish failure throws ImmediateRequeueAmqpException")
    void receive_retryPublishFails_throwsImmediateRequeue() {
        doThrow(new IllegalStateException("driver-service timeout"))
                .when(matchingService).matchTrip(any());
        doThrow(new IllegalStateException("broker down"))
                .when(eventPublisher).sendRetry(any());

        assertThatThrownBy(() -> consumer.receive(validTripRequestedMessage(UUID.randomUUID().toString())))
                .isInstanceOf(ImmediateRequeueAmqpException.class);
    }

    @Test
    @DisplayName("MatchingEventPublisher and MatchingConfig enforce broker confirms and retry/DLQ topology")
    void publisherAndTopology_behaveAsExpected() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        var publisher = new MatchingEventPublisher(template, jsonMapper, properties);
        verify(template).setMandatory(true);

        var result = new MatchingResultEvent(
                UUID.randomUUID().toString(),
                "DriverAssigned",
                UUID.randomUUID().toString(),
                Instant.parse("2026-11-18T07:10:01Z"),
                new MatchingResultEvent.Payload(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        "CAR",
                        "51A-888.88",
                        Instant.parse("2026-11-18T07:10:01Z"),
                        null));

        doAnswer(inv -> {
            CorrelationData cd = inv.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertThatCode(() -> publisher.publishResult(result)).doesNotThrowAnyException();

        Message retryMsg = validTripRequestedMessage(UUID.randomUUID().toString());
        doAnswer(inv -> {
            CorrelationData cd = inv.getArgument(3);
            cd.setReturned(new ReturnedMessage(retryMsg, 312, "NO_ROUTE", "matching.delivery", "matching.retry"));
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> publisher.sendRetry(retryMsg))
                .isInstanceOf(IllegalStateException.class);

        MatchingConfig config = new MatchingConfig();
        Declarables declarables = config.matchingMessagingTopology(properties);
        assertThat(declarables.getDeclarablesByType(Queue.class))
                .extracting(Queue::getName)
                .containsExactlyInAnyOrder("matching.commands", "matching.retry", "matching.dlq");
        assertThat(config.driverRestClient(properties)).isNotNull();
        assertThat(config.matchingClock()).isNotNull();
    }
}
