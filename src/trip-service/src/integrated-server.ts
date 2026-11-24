import { PrismaClient } from "@prisma/client";
import type { Server } from "node:http";
import { createApp, createServiceLogger } from "./app";
import { PrismaTripRepository } from "./repositories/PrismaTripRepository";
import { PrismaOutboxRepository, PrismaProcessedEventRepository } from "./repositories/PrismaMessagingRepositories";
import { createDurableTripService, handleDurableTripEvent } from "./services/durableTripService";
import { RabbitMQConnection } from "./messaging/RabbitMQConnection";
import { RabbitMQEventPublisher } from "./messaging/RabbitMQEventPublisher";
import { TripEventConsumer } from "./messaging/tripEventConsumer";
import { OutboxWorker } from "./outbox/outboxWorker";
import { createRideRoutes, demoFailpointsEnabled } from "./routes/rideRoutes";
import { handleRideResult } from "./services/rideWorkflow";

function brokerUrl(): string | undefined {
  if (process.env.RABBITMQ_URL) return process.env.RABBITMQ_URL;
  if (!process.env.RABBITMQ_HOST) return undefined;
  const url = new URL("amqp://" + process.env.RABBITMQ_HOST);
  url.username = process.env.RABBITMQ_USER ?? "";
  url.password = process.env.RABBITMQ_PASSWORD ?? "";
  return url.toString();
}

/** Starts the integrated persistent API, worker and consumer. */
async function main(): Promise<void> {
  const logger = createServiceLogger();
  const db = new PrismaClient();
  const connection = new RabbitMQConnection();
  let server: Server | undefined;
  let consumer: TripEventConsumer | undefined;
  let worker: OutboxWorker | undefined;
  let stopping = false;
  const stop = async (): Promise<void> => {
    if (stopping) return;
    stopping = true;
    const deadline = setTimeout(() => process.exit(1), 15000);
    deadline.unref();
    try {
      if (server) await new Promise<void>(resolve => server!.close(() => resolve()));
      await consumer?.stop().catch(error => logger.error({ err: error }, "Consumer shutdown"));
      await worker?.stop();
    } finally {
      await connection.close().catch(() => undefined);
      await db.$disconnect();
      clearTimeout(deadline);
    }
  };
  const fail = (error: unknown): void => {
    logger.error({ err: error }, "Integrated service stopped");
    process.exitCode = 1;
    void stop().catch(error => logger.error({ err: error }, "Shutdown failed"));
  };
  try {
    const port = Number(process.env.PORT ?? 8083);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid PORT");
    await db.$connect();
    const channel = await connection.connect(brokerUrl(), true);
    channel.on("close", () => { if (!stopping) fail(new Error("Broker disconnected; restart required")); });
    const outbox = new PrismaOutboxRepository(db);
    consumer = new TripEventConsumer(channel, new PrismaTripRepository(db), outbox,
      new PrismaProcessedEventRepository(db), event => handleDurableTripEvent(db, event),
      event => handleRideResult(db, event));
    worker = new OutboxWorker(outbox, new RabbitMQEventPublisher(channel), 100, async (eventType, tripId, correlationId) => {
      if (eventType !== "PaymentRefundRequested" || !demoFailpointsEnabled()) return;
      const marker = await db.trip.updateMany({
        where: { id: tripId, demoFault: "F02", demoFaultAt: null, driverReleased: true, paymentState: "REFUND_PENDING" },
        data: { demoFaultAt: new Date() },
      });
      if (marker.count === 1) {
        const details = JSON.stringify({ service: "trip-service", event: "F02_BEFORE_REFUND_PUBLISH", tripId, correlationId });
        process.stderr.write(details + "\n", () => process.exit(42));
        await new Promise<never>(() => { /* The crash precedes publication. */ });
      }
    });
    await consumer.start();
    worker.start();
    server = createApp({
      service: createDurableTripService(db), logger,
      integratedRoutes: createRideRoutes(db),
      enableLegacyRoutes: process.env.TRIP_ENABLE_LEGACY_ROUTES !== "false",
    }).listen(port);
    server.on("error", fail);
    server.on("listening", () => logger.info({ port }, "Integrated Trip Service ready"));
    process.once("SIGINT", () => { void stop().catch(fail); });
    process.once("SIGTERM", () => { void stop().catch(fail); });
  } catch (error) { fail(error); }
}
void main();
