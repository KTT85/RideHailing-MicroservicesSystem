import { randomUUID } from "node:crypto";
import { InMemoryTripRepository } from "../repositories/InMemoryTripRepository";
import { InMemoryProcessedEventRepository } from "../repositories/InMemoryProcessedEventRepository";
import { InMemoryOutboxRepository } from "../outbox/InMemoryOutboxRepository";
import { OutboxEventPublisher } from "../outbox/OutboxEventPublisher";
import { OutboxWorker } from "../outbox/outboxWorker";
import { RabbitMQConnection, messagingLogger } from "./RabbitMQConnection";
import { RabbitMQEventPublisher } from "./RabbitMQEventPublisher";
import { TripEventConsumer } from "./tripEventConsumer";

/** Runs consumer and worker together so the demo shares in-memory state. */
async function main(): Promise<void> {
  const connection = new RabbitMQConnection();
  const channel = await connection.connect();
  const trips = new InMemoryTripRepository();
  const outbox = new InMemoryOutboxRepository();
  const consumer = new TripEventConsumer(channel, trips, outbox, new InMemoryProcessedEventRepository());
  const worker = new OutboxWorker(outbox, new RabbitMQEventPublisher(channel));
  const trip = await trips.create({ passengerId: randomUUID(), pickup: { lat: 10, lng: 106 },
    dropoff: { lat: 11, lng: 107 }, idempotencyKey: randomUUID() });
  const completed = await trips.updateStatus(trip.id, "COMPLETED", { fare: 50000 });
  const correlationId = randomUUID();
  await new OutboxEventPublisher(outbox).publish({ eventId: randomUUID(), eventType: "trip.completed",
    correlationId, source: "trip-service", occurredAt: new Date().toISOString(), payload: completed });
  await consumer.start();
  worker.start();
  messagingLogger.info({ correlationId, tripId: trip.id }, "Demo ready: publish payment.failed or payment.completed for this trip");
  let stopping = false;
  const stop = (): void => {
    if (stopping) return;
    stopping = true;
    void (async () => {
      try { await consumer.stop(); }
      finally { try { await worker.stop(); } finally { await connection.close(); } }
    })()
      .catch(error => { messagingLogger.error({ err: error }, "Shutdown failed"); process.exitCode = 1; });
  };
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);
  channel.on("close", () => { if (!stopping) { process.exitCode = 1; stop(); } });
}

void main().catch(error => { messagingLogger.error({ err: error }, "Messaging startup failed"); process.exitCode = 1; });
