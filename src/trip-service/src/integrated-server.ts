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
    const port = Number(process.env.PORT ?? 3000);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid PORT");
    await db.$connect();
    const channel = await connection.connect();
    channel.on("close", () => { if (!stopping) fail(new Error("Broker disconnected; restart required")); });
    const outbox = new PrismaOutboxRepository(db);
    consumer = new TripEventConsumer(channel, new PrismaTripRepository(db), outbox,
      new PrismaProcessedEventRepository(db), event => handleDurableTripEvent(db, event));
    worker = new OutboxWorker(outbox, new RabbitMQEventPublisher(channel));
    await consumer.start();
    worker.start();
    server = createApp({ service: createDurableTripService(db), logger }).listen(port);
    server.on("error", fail);
    server.on("listening", () => logger.info({ port }, "Integrated Trip Service ready"));
    process.once("SIGINT", () => { void stop().catch(fail); });
    process.once("SIGTERM", () => { void stop().catch(fail); });
  } catch (error) { fail(error); }
}
void main();
