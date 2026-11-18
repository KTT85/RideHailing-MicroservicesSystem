package vn.edu.ds03.driver.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;
import vn.edu.ds03.driver.config.DriverMessagingProperties;
import vn.edu.ds03.driver.config.DriverRabbitConfig;
import vn.edu.ds03.driver.event.DriverInboundEvent;
import vn.edu.ds03.driver.exception.InvalidDriverEventException;
import vn.edu.ds03.driver.messaging.consumer.DriverEventConsumer;
import vn.edu.ds03.driver.messaging.publisher.ConfirmedRabbitPublisher;
import vn.edu.ds03.driver.messaging.publisher.DriverOutboxPublisher;
import vn.edu.ds03.driver.messaging.publisher.DriverOutboxScheduler;
import vn.edu.ds03.driver.service.DriverEventService;

class DriverEventConsumerTest {

    private final DriverEventService eventService = mock(DriverEventService.class);
    private final ConfirmedRabbitPublisher rabbitPublisher = mock(ConfirmedRabbitPublisher.class);
    private final DriverOutboxPublisher outboxPublisher = mock(DriverOutboxPublisher.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final DriverMessagingProperties properties = new DriverMessagingProperties(
            "ride.events",
            "driver.delivery",
            "driver.commands",
            "driver.retry",
            "driver.dlq",
            5000,
            3,
            65536,
            2000L,
            5L,
            0.0,
            false);
    private final DriverEventConsumer consumer = new DriverEventConsumer(
            eventService, jsonMapper, rabbitPublisher, outboxPublisher, properties);

    private Message validReleaseMessage() {
        var event = new DriverInboundEvent(
                UUID.randomUUID().toString(),
                "DriverReleaseRequested",
                UUID.randomUUID().toString(),
                Instant.parse("2026-11-18T06:30:00Z"),
                null,
                new DriverInboundEvent.Payload(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        "payment_failed"));
        byte[] body = jsonMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        return MessageBuilder.withBody(body).build();
    }

    @Test
    @DisplayName("valid release event processes cleanly and flushes outbox best-effort")
    void receive_validEventWithOutbox_processesAndFlushesOutbox() {
        when(eventService.processEvent(any())).thenReturn(true);

        consumer.receive(validReleaseMessage());

        verify(eventService).processEvent(any());
        verify(outboxPublisher).publishDueEvents();
        verifyNoInteractions(rabbitPublisher);
    }

    @Test
    @DisplayName("best-effort outbox flush error does not fail already committed event")
    void receive_outboxFlushThrows_doesNotTriggerConsumerRetry() {
        when(eventService.processEvent(any())).thenReturn(true);
        doThrow(new IllegalStateException("broker hiccup")).when(outboxPublisher).publishDueEvents();

        assertThatCode(() -> consumer.receive(validReleaseMessage())).doesNotThrowAnyException();

        verifyNoInteractions(rabbitPublisher);
    }

    @Test
    @DisplayName("malformed JSON, null JSON, and oversized payloads are rejected directly to DLQ")
    void receive_malformedOrOversizedMessage_rejectsToDlq() {
        assertThatThrownBy(() -> consumer.receive(
                new Message("not-json".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThatThrownBy(() -> consumer.receive(
                new Message("null".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThatThrownBy(() -> consumer.receive(new Message(new byte[0])))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThatThrownBy(() -> consumer.receive(new Message(new byte[65537])))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(eventService, rabbitPublisher, outboxPublisher);
    }

    @Test
    @DisplayName("domain InvalidDriverEventException rejects directly to DLQ without retry")
    void receive_domainConflict_rejectsToDlqWithoutRetry() {
        doThrow(new InvalidDriverEventException("trip mismatch"))
                .when(eventService).processEvent(any());

        assertThatThrownBy(() -> consumer.receive(validReleaseMessage()))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(rabbitPublisher, outboxPublisher);
    }

    @Test
    @DisplayName("concurrent duplicate DataIntegrityViolationException ACKs cleanly when already processed")
    void receive_duplicateConstraintRace_acksWhenAlreadyProcessed() {
        doThrow(new DataIntegrityViolationException("uq_processed_events"))
                .when(eventService).processEvent(any());
        when(eventService.isAlreadyProcessed(anyString())).thenReturn(true);

        assertThatCode(() -> consumer.receive(validReleaseMessage())).doesNotThrowAnyException();

        verifyNoInteractions(rabbitPublisher, outboxPublisher);
    }

    @Test
    @DisplayName("transient failure publishes persistent retry message with incremented counter")
    void receive_transientFailure_publishesPersistentRetry() {
        doThrow(new IllegalStateException("DB lock timeout"))
                .when(eventService).processEvent(any());

        consumer.receive(validReleaseMessage());

        verify(rabbitPublisher).send(
                eq("driver.delivery"),
                eq("driver.retry"),
                argThat(msg -> Integer.valueOf(1).equals(
                        msg.getMessageProperties().getHeader(DriverEventConsumer.RETRY_HEADER))
                        && msg.getMessageProperties().getDeliveryMode() == MessageDeliveryMode.PERSISTENT),
                eq(true));
        verify(outboxPublisher, never()).publishDueEvents();
    }

    @Test
    @DisplayName("exhausted retry count rejects message to DLQ")
    void receive_exhaustedRetries_rejectsToDlq() {
        doThrow(new IllegalStateException("DB unavailable"))
                .when(eventService).processEvent(any());
        Message message = validReleaseMessage();
        message.getMessageProperties().setHeader(DriverEventConsumer.RETRY_HEADER, 3);

        assertThatThrownBy(() -> consumer.receive(message))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(rabbitPublisher);
    }

    @Test
    @DisplayName("tampered negative or non-numeric retry header is rejected to DLQ")
    void receive_invalidRetryHeader_rejectsToDlq() {
        doThrow(new IllegalStateException("DB unavailable"))
                .when(eventService).processEvent(any());

        Message negative = validReleaseMessage();
        negative.getMessageProperties().setHeader(DriverEventConsumer.RETRY_HEADER, -5);
        assertThatThrownBy(() -> consumer.receive(negative))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        Message nonNumber = validReleaseMessage();
        nonNumber.getMessageProperties().setHeader(DriverEventConsumer.RETRY_HEADER, "nan");
        assertThatThrownBy(() -> consumer.receive(nonNumber))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        Message floating = validReleaseMessage();
        floating.getMessageProperties().setHeader(DriverEventConsumer.RETRY_HEADER, Double.NaN);
        assertThatThrownBy(() -> consumer.receive(floating))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        Message aboveBound = validReleaseMessage();
        aboveBound.getMessageProperties().setHeader(DriverEventConsumer.RETRY_HEADER, 21);
        assertThatThrownBy(() -> consumer.receive(aboveBound))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        Message longOverflow = validReleaseMessage();
        longOverflow.getMessageProperties().setHeader(DriverEventConsumer.RETRY_HEADER, 0x1_0000_0000L);
        assertThatThrownBy(() -> consumer.receive(longOverflow))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(rabbitPublisher);
    }

    @Test
    @DisplayName("retry publish failure throws ImmediateRequeueAmqpException to preserve delivery")
    void receive_retryPublishFails_throwsImmediateRequeue() {
        doThrow(new IllegalStateException("DB unavailable"))
                .when(eventService).processEvent(any());
        doThrow(new IllegalStateException("Broker down"))
                .when(rabbitPublisher).send(anyString(), anyString(), any(), anyBoolean());

        assertThatThrownBy(() -> consumer.receive(validReleaseMessage()))
                .isInstanceOf(ImmediateRequeueAmqpException.class);
    }

    @Test
    @DisplayName("ConfirmedRabbitPublisher enforces broker ACK and optional mandatory route check")
    void confirmedPublisher_checksAckAndOptionalRoute() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        var publisher = new ConfirmedRabbitPublisher(template);
        verify(template).setMandatory(true);
        Message sample = validReleaseMessage();

        @SuppressWarnings("unchecked")
        ObjectProvider<RabbitTemplate> emptyProvider = mock(ObjectProvider.class);
        var unconfigured = new ConfirmedRabbitPublisher(emptyProvider);
        assertThatThrownBy(() -> unconfigured.send("ride.events", "DriverReleased", sample, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RabbitTemplate");

        var jitteredProps = new DriverMessagingProperties(
                "ride.events", "driver.delivery", "driver.commands", "driver.retry", "driver.dlq",
                5000, 3, 65536, 2000L, Long.MAX_VALUE, 0.25, false);
        assertThat(jitteredProps.computeBackoffSeconds(5)).isEqualTo(3600L);

        doAnswer(inv -> {
            CorrelationData cd = inv.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertThatCode(() -> publisher.send("ride.events", "DriverReleased", sample, false))
                .doesNotThrowAnyException();

        doAnswer(inv -> {
            CorrelationData cd = inv.getArgument(3);
            cd.setReturned(new ReturnedMessage(sample, 312, "NO_ROUTE", "driver.delivery", "driver.retry"));
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertThatCode(() -> publisher.send("ride.events", "DriverReleased", sample, false))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> publisher.send("driver.delivery", "driver.retry", sample, true))
                .isInstanceOf(IllegalStateException.class);

        doAnswer(inv -> {
            CorrelationData cd = inv.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> publisher.send("ride.events", "DriverReleased", sample, false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("DriverRabbitConfig declares durable commands, retry TTL, and DLQ queues with bindings")
    void topology_declaresExpectedExchangesQueuesAndBindings() {
        DriverRabbitConfig config = new DriverRabbitConfig();
        Declarables declarables = config.driverMessagingTopology(properties);

        var queues = declarables.getDeclarablesByType(Queue.class);
        assertThat(queues).extracting(Queue::getName)
                .containsExactlyInAnyOrder("driver.commands", "driver.retry", "driver.dlq");

        Queue commands = queues.stream()
                .filter(q -> "driver.commands".equals(q.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(commands.getArguments())
                .containsEntry("x-dead-letter-exchange", "driver.delivery")
                .containsEntry("x-dead-letter-routing-key", "driver.dlq");

        Queue retry = queues.stream()
                .filter(q -> "driver.retry".equals(q.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(retry.getArguments())
                .containsEntry("x-message-ttl", 5000)
                .containsEntry("x-dead-letter-exchange", "driver.delivery")
                .containsEntry("x-dead-letter-routing-key", "driver.commands");

        DriverOutboxScheduler scheduler = new DriverOutboxScheduler(outboxPublisher);
        scheduler.pollOutbox();
        verify(outboxPublisher).publishDueEvents();
    }
}
