import { Prisma, PrismaClient } from "@prisma/client";
import { TripService } from "./tripService";
import { PrismaTripRepository } from "../repositories/PrismaTripRepository";
import { PrismaOutboxRepository, PrismaProcessedEventRepository } from "../repositories/PrismaMessagingRepositories";
import { OutboxEventPublisher } from "../outbox/OutboxEventPublisher";
import { tripPaymentSaga } from "../saga/tripPaymentSaga";
import type { EventEnvelope } from "../messaging/IEventPublisher";
import { TripServiceError } from "../middleware/errorHandler";

/** Serializes mutations across API and consumers; suitable for the single-service demo.
 * The transaction-scoped database lock also protects concurrent API instances.
 */
export async function tripTransaction<T>(db: PrismaClient, work: (tx: Prisma.TransactionClient) => Promise<T>): Promise<T> {
  return db.$transaction(async tx => {
    await tx.$executeRaw`SELECT pg_advisory_xact_lock(731031)`;
    return work(tx);
  }, { maxWait: 10000, timeout: 20000 });
}

/** Wraps every public operation in a database transaction with a shared outbox. */
export function createDurableTripService(db: PrismaClient): TripService {
  const make = (tx: Prisma.TransactionClient): TripService => new TripService(
    new PrismaTripRepository(tx), new OutboxEventPublisher(new PrismaOutboxRepository(tx)));
  const methods = new Set(["createTrip", "getTrip", "listTrips", "acceptTrip", "rejectTrip", "startTrip", "completeTrip", "cancelTrip"]);
  return new Proxy(make(db), {
    get(target, property, receiver): unknown {
      if (typeof property !== "string" || !methods.has(property)) return Reflect.get(target, property, receiver);
      return (...args: unknown[]) => tripTransaction(db, async tx => {
        if (property === "completeTrip") {
          const trip = await new PrismaTripRepository(tx).findById(args[0] as string);
          const fare = args[2] ?? trip?.fare;
          if (typeof fare !== "number" || !Number.isFinite(fare) || fare < 0) {
            throw new TripServiceError(400, "INVALID_FARE", "A non-negative fare is required for payment");
          }
        }
        const service = make(tx);
        return Reflect.apply(Reflect.get(service, property), service, args) as Promise<unknown>;
      });
    },
  });
}

/** Commits all saga actions and dedup together; rollback leaves no RAM checkpoint. */
export async function handleDurableTripEvent(db: PrismaClient, event: EventEnvelope<Record<string, unknown>>): Promise<void> {
  await tripTransaction(db, async tx => {
    const processed = new PrismaProcessedEventRepository(tx);
    if (await processed.has(event.eventId)) return;
    const trips = new PrismaTripRepository(tx);
    const outbox = new PrismaOutboxRepository(tx);
    const actions = tripPaymentSaga(event);
    const trip = await trips.findById(actions[0].tripId);
    if (!trip) throw new Error("Trip not found");
    const allowed = event.eventType === "driver.assigned"
      ? ["REQUESTED", "MATCHING", "DRIVER_ASSIGNED"] : ["COMPLETED", "PAYMENT_PENDING"];
    if (!allowed.includes(trip.status) || (event.eventType.startsWith("payment.") && trip.paymentId)) {
      throw new Error("Event incompatible with trip state or payment already settled");
    }
    for (const action of actions) {
      if (action.kind === "update") await trips.updateStatus(action.tripId, action.status, action.patch);
      else await outbox.add({ aggregateId: action.tripId, eventType: action.eventType,
        payload: action.payload, correlationId: action.correlationId });
    }
    await processed.markProcessed(event.eventId);
  });
}
