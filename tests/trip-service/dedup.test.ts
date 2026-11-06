import * as amqp from "amqplib";
import type { ConfirmChannel, ConsumeMessage } from "amqplib";
import { RabbitMQConnection, RETRY_QUEUES, TRIP_QUEUE } from "../../src/trip-service/src/messaging/RabbitMQConnection";
import { RabbitMQEventPublisher } from "../../src/trip-service/src/messaging/RabbitMQEventPublisher";
import { TripEventConsumer } from "../../src/trip-service/src/messaging/tripEventConsumer";
import { InMemoryTripRepository } from "../../src/trip-service/src/repositories/InMemoryTripRepository";
import { InMemoryProcessedEventRepository } from "../../src/trip-service/src/repositories/InMemoryProcessedEventRepository";
import { InMemoryOutboxRepository } from "../../src/trip-service/src/outbox/InMemoryOutboxRepository";
import { OutboxWorker } from "../../src/trip-service/src/outbox/outboxWorker";

jest.mock("amqplib");

test("durable handler commits before ACK and retries a rejected transaction", async () => {
  const f = await fixture();
  let commit: (() => void) | undefined;
  const handler = jest.fn(() => new Promise<void>(resolve => { commit = resolve; }));
  const consumer = new TripEventConsumer(f.channel, f.trips, f.outbox, f.processed, handler);
  const pending = consumer.handleMessage(f.message());
  await Promise.resolve();
  expect(f.mock.ack).not.toHaveBeenCalled();
  expect(handler).toHaveBeenCalledWith(f.envelope);
  commit!();
  await pending;
  expect(f.mock.ack).toHaveBeenCalledTimes(1);
  expect(f.update).not.toHaveBeenCalled();
  handler.mockRejectedValueOnce(new Error("transaction rolled back"));
  await consumer.handleMessage(f.message());
  expect(f.mock.sendToQueue).toHaveBeenCalledWith(RETRY_QUEUES[0], expect.any(Buffer), expect.any(Object), expect.any(Function));
});

function mockChannel() {
  return { assertExchange: jest.fn().mockResolvedValue({}), assertQueue: jest.fn().mockResolvedValue({}),
    bindQueue: jest.fn().mockResolvedValue({}), prefetch: jest.fn().mockResolvedValue({}), on: jest.fn(),
    consume: jest.fn().mockResolvedValue({ consumerTag: "consumer" }), cancel: jest.fn().mockResolvedValue({}),
    ack: jest.fn(), nack: jest.fn(),
    sendToQueue: jest.fn((_queue, _content, _options, callback: (error: Error | null) => void) => { callback(null); return true; }),
    publish: jest.fn((_exchange, _key, _content, _options, callback: (error: Error | null) => void) => { callback(null); return true; }),
  };
}

async function fixture() {
  const mock = mockChannel();
  const channel = mock as unknown as ConfirmChannel;
  const connectionMock = { createConfirmChannel: jest.fn().mockResolvedValue(channel), on: jest.fn(), close: jest.fn().mockResolvedValue(undefined) };
  jest.mocked(amqp.connect).mockResolvedValue(connectionMock as unknown as Awaited<ReturnType<typeof amqp.connect>>);
  await new RabbitMQConnection().connect("amqp://localhost");
  const trips = new InMemoryTripRepository();
  const trip = await trips.create({ passengerId: "p1", pickup: { lat: 0, lng: 0 }, dropoff: { lat: 1, lng: 1 }, idempotencyKey: "key" });
  await trips.updateStatus(trip.id, "COMPLETED");
  const update = jest.spyOn(trips, "updateStatus");
  const outbox = new InMemoryOutboxRepository();
  const processed = new InMemoryProcessedEventRepository();
  const consumer = new TripEventConsumer(channel, trips, outbox, processed);
  const envelope = { eventId: "e1", eventType: "payment.failed", correlationId: "c1", source: "payment-service",
    occurredAt: "2026-10-30T00:00:00Z", payload: { tripId: trip.id, reason: "declined" } };
  const message = (count = 0): ConsumeMessage => ({ content: Buffer.from(JSON.stringify(envelope)),
    properties: { headers: { "x-retry-count": count }, contentType: "application/json", contentEncoding: undefined,
      deliveryMode: 2, priority: undefined, correlationId: "c1", replyTo: undefined, expiration: undefined,
      messageId: "e1", timestamp: undefined, type: envelope.eventType, userId: undefined, appId: undefined, clusterId: undefined },
    fields: { consumerTag: "consumer", deliveryTag: 1, redelivered: false, exchange: "ridehailing.events", routingKey: envelope.eventType } });
  return { mock, channel, trips, trip, update, outbox, processed, consumer, envelope, message };
}

test("concurrent duplicate eventIds execute compensation exactly once", async () => {
  const f = await fixture();
  await Promise.all([f.consumer.handleMessage(f.message()), f.consumer.handleMessage(f.message())]);
  expect(f.update.mock.calls.map(call => call[1])).toEqual(["COMPENSATING", "CANCELLED"]);
  expect(await f.processed.has("e1")).toBe(true);
  expect(await f.outbox.listUnpublished(10)).toHaveLength(1);
  expect(f.mock.ack).toHaveBeenCalledTimes(2);
});

test("topology routes retry TTL expiration directly to Trip queue", async () => {
  const f = await fixture();
  for (const [index, ttl] of [5000, 15000, 60000].entries()) {
    expect(f.mock.assertQueue).toHaveBeenCalledWith(RETRY_QUEUES[index], { durable: true,
      arguments: { "x-message-ttl": ttl, "x-dead-letter-exchange": "", "x-dead-letter-routing-key": TRIP_QUEUE } });
  }
});

test.each([0, 1, 2])("failure at retry count %i uses corresponding queue", async count => {
  const f = await fixture();
  f.update.mockRejectedValue(new Error("database unavailable"));
  await f.consumer.handleMessage(f.message(count));
  expect(f.mock.sendToQueue).toHaveBeenCalledWith(RETRY_QUEUES[count], expect.any(Buffer),
    expect.objectContaining({ persistent: true, headers: { "x-retry-count": count + 1 } }), expect.any(Function));
  expect(await f.processed.has("e1")).toBe(false);
  expect(f.mock.ack).toHaveBeenCalledTimes(1);
});

test("third retry failure dead-letters original without requeue", async () => {
  const f = await fixture();
  f.update.mockRejectedValue(new Error("database unavailable"));
  const message = f.message(3);
  await f.consumer.handleMessage(message);
  expect(f.mock.nack).toHaveBeenCalledWith(message, false, false);
  expect(f.mock.sendToQueue).not.toHaveBeenCalled();
});

test("failed retry confirmation requeues original and does not acknowledge", async () => {
  const f = await fixture();
  f.update.mockRejectedValue(new Error("database unavailable"));
  f.mock.sendToQueue.mockImplementation((_q, _c, _p, callback) => { callback(new Error("broker failed")); return false; });
  const message = f.message();
  await expect(f.consumer.handleMessage(message)).rejects.toThrow("broker failed");
  expect(f.mock.nack).toHaveBeenCalledWith(message, false, true);
  expect(f.mock.ack).not.toHaveBeenCalled();
});

test("original delivery stays unacknowledged until retry broker confirmation", async () => {
  const f = await fixture();
  f.update.mockRejectedValue(new Error("database unavailable"));
  let confirm: ((error: Error | null) => void) | undefined;
  let signalSent: (() => void) | undefined;
  const sent = new Promise<void>(resolve => { signalSent = resolve; });
  f.mock.sendToQueue.mockImplementation((_q, _c, _p, callback) => {
    confirm = callback;
    signalSent?.();
    return true;
  });
  const handling = f.consumer.handleMessage(f.message());
  await sent;
  expect(f.mock.ack).not.toHaveBeenCalled();
  confirm?.(null);
  await handling;
  expect(f.mock.ack).toHaveBeenCalledTimes(1);
});

test("outbox insertion retry resumes compensation without replaying status updates", async () => {
  const f = await fixture();
  jest.spyOn(f.outbox, "add").mockRejectedValueOnce(new Error("outbox unavailable"));
  await f.consumer.handleMessage(f.message());
  await f.consumer.handleMessage(f.message(1));
  expect(f.update).toHaveBeenCalledTimes(2);
  expect(await f.outbox.listUnpublished(10)).toHaveLength(1);
});

test("outbox keeps unconfirmed records and retries with the same eventId", async () => {
  const f = await fixture();
  await f.outbox.add({ aggregateId: f.trip.id, eventType: "trip.cancelled", correlationId: "c1", payload: { tripId: f.trip.id } });
  f.mock.publish.mockImplementationOnce((_e, _k, _c, _p, callback) => { callback(new Error("nack")); return true; });
  const worker = new OutboxWorker(f.outbox, new RabbitMQEventPublisher(f.channel));
  await worker.runOnce();
  expect(await f.outbox.listUnpublished(10)).toHaveLength(1);
  await worker.runOnce();
  expect(await f.outbox.listUnpublished(10)).toHaveLength(0);
  const first = JSON.parse((f.mock.publish.mock.calls[0][2] as Buffer).toString()) as { eventId: string };
  const second = JSON.parse((f.mock.publish.mock.calls[1][2] as Buffer).toString()) as { eventId: string };
  expect(first.eventId).toBe(second.eventId);
});

test("payment.completed persists paymentId without requesting another payment", async () => {
  const f = await fixture();
  const message = f.message();
  message.content = Buffer.from(JSON.stringify({ ...f.envelope, eventType: "payment.completed",
    payload: { tripId: f.trip.id, paymentId: "payment-1", amount: 50 } }));
  await f.consumer.handleMessage(message);
  expect(await f.trips.findById(f.trip.id)).toMatchObject({ status: "COMPLETED", paymentId: "payment-1" });
  expect(await f.outbox.listUnpublished(10)).toEqual([]);
});

test("driver.assigned stores driverId and moves matching trip forward", async () => {
  const f = await fixture();
  await f.trips.updateStatus(f.trip.id, "MATCHING");
  const message = f.message();
  message.content = Buffer.from(JSON.stringify({ ...f.envelope, eventType: "driver.assigned",
    payload: { tripId: f.trip.id, driverId: "driver-1", assignedAt: f.envelope.occurredAt } }));
  await f.consumer.handleMessage(message);
  expect(await f.trips.findById(f.trip.id)).toMatchObject({ status: "DRIVER_ASSIGNED", driverId: "driver-1" });
});

test("failed dedup write does not duplicate already queued compensation on retry", async () => {
  const f = await fixture();
  jest.spyOn(f.processed, "markProcessed").mockRejectedValueOnce(new Error("dedup unavailable"));
  await f.consumer.handleMessage(f.message());
  await f.consumer.handleMessage(f.message(1));
  expect(f.update).toHaveBeenCalledTimes(2);
  expect(await f.outbox.listUnpublished(10)).toHaveLength(1);
  expect(await f.processed.has("e1")).toBe(true);
});

test("malformed JSON is dead-lettered after retry budget without business changes", async () => {
  const f = await fixture();
  const message = f.message(3);
  message.content = Buffer.from("{invalid");
  await f.consumer.handleMessage(message);
  expect(f.update).not.toHaveBeenCalled();
  expect(f.mock.nack).toHaveBeenCalledWith(message, false, false);
});
