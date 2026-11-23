import { randomUUID } from "node:crypto";
import request from "supertest";
import pino from "pino";
import type { Express } from "express";
import { createApp } from "../../src/trip-service/src/app";
import type { EventEnvelope, IEventPublisher } from "../../src/trip-service/src/messaging/IEventPublisher";
import { InMemoryTripRepository } from "../../src/trip-service/src/repositories/InMemoryTripRepository";

class RecordingEventPublisher implements IEventPublisher {
  readonly events: EventEnvelope[] = [];

  /** Records an event envelope for assertions. */
  async publish<T>(envelope: EventEnvelope<T>): Promise<void> {
    this.events.push(envelope);
  }
}

function requestHeaders(idempotencyKey: string = randomUUID()): Record<string, string> {
  return {
    "X-Correlation-Id": randomUUID(),
    "Idempotency-Key": idempotencyKey,
  };
}

async function createTrip(app: Express): Promise<{ id: string; passengerId: string }> {
  const passengerId = randomUUID();
  const response = await request(app)
    .post("/api/trips")
    .set(requestHeaders())
    .send({
      passengerId,
      pickup: { lat: 10.7769, lng: 106.7009, address: "District 1" },
      dropoff: { lat: 10.8231, lng: 106.6297, address: "Tan Binh" },
    });
  expect(response.status).toBe(201);
  return { id: response.body.data.id as string, passengerId };
}

describe("Trip REST API", () => {
  let app: Express;
  let repository: InMemoryTripRepository;
  let publisher: RecordingEventPublisher;

  beforeEach(() => {
    repository = new InMemoryTripRepository();
    publisher = new RecordingEventPublisher();
    app = createApp({ repository, eventPublisher: publisher, logger: pino({ enabled: false }) });
  });

  it("creates a trip and returns the existing trip for a repeated idempotency key", async () => {
    const headers = requestHeaders("create-once");
    const body = {
      passengerId: randomUUID(),
      pickup: { lat: 10.7769, lng: 106.7009 },
      dropoff: { lat: 10.8231, lng: 106.6297 },
    };

    const first = await request(app).post("/api/trips").set(headers).send(body);
    const replay = await request(app).post("/api/trips").set(headers).send(body);

    expect(first.status).toBe(201);
    expect(first.body.data.status).toBe("REQUESTED");
    expect(replay.status).toBe(201);
    expect(replay.body.data.id).toBe(first.body.data.id);
    expect(publisher.events).toHaveLength(1);
  });

  it("gets a trip by id", async () => {
    const created = await createTrip(app);
    const response = await request(app)
      .get(`/api/trips/${created.id}`)
      .set("X-Correlation-Id", randomUUID());

    expect(response.status).toBe(200);
    expect(response.body.data.id).toBe(created.id);
    expect(response.body.data.passengerId).toBe(created.passengerId);
  });

  it("accepts a trip through matching and driver assignment", async () => {
    const created = await createTrip(app);
    const driverId = randomUUID();
    const response = await request(app)
      .post(`/api/trips/${created.id}/accept`)
      .set("X-Correlation-Id", randomUUID())
      .send({ driverId });

    expect(response.status).toBe(200);
    expect(response.body.data.status).toBe("ACCEPTED");
    expect(response.body.data.driverId).toBe(driverId);
    expect(publisher.events.map((event) => event.eventType)).toEqual([
      "trip.requested",
      "trip.matching",
      "trip.driver_assigned",
      "trip.accepted",
    ]);
  });

  it("rejects a trip after matching and driver assignment", async () => {
    const created = await createTrip(app);
    const driverId = randomUUID();
    const response = await request(app)
      .post(`/api/trips/${created.id}/reject`)
      .set("X-Correlation-Id", randomUUID())
      .send({ driverId });

    expect(response.status).toBe(200);
    expect(response.body.data.status).toBe("REJECTED");
    expect(response.body.data.driverId).toBe(driverId);
  });

  it("starts an accepted trip", async () => {
    const created = await createTrip(app);
    await request(app).post(`/api/trips/${created.id}/accept`).set("X-Correlation-Id", randomUUID());
    const response = await request(app)
      .post(`/api/trips/${created.id}/start`)
      .set("X-Correlation-Id", randomUUID());

    expect(response.status).toBe(200);
    expect(response.body.data.status).toBe("IN_PROGRESS");
    expect(response.body.data.startedAt).toEqual(expect.any(String));
  });

  it("completes an in-progress trip", async () => {
    const created = await createTrip(app);
    await request(app).post(`/api/trips/${created.id}/accept`).set("X-Correlation-Id", randomUUID());
    await request(app).post(`/api/trips/${created.id}/start`).set("X-Correlation-Id", randomUUID());
    const response = await request(app)
      .post(`/api/trips/${created.id}/complete`)
      .set("X-Correlation-Id", randomUUID())
      .send({ fare: 125.5 });

    expect(response.status).toBe(200);
    expect(response.body.data.status).toBe("COMPLETED");
    expect(response.body.data.fare).toBe(125.5);
    expect(response.body.data.completedAt).toEqual(expect.any(String));
  });

  it("cancels a trip before it starts", async () => {
    const created = await createTrip(app);
    const response = await request(app)
      .post(`/api/trips/${created.id}/cancel`)
      .set("X-Correlation-Id", randomUUID())
      .send({ reason: "Passenger changed plans" });

    expect(response.status).toBe(200);
    expect(response.body.data.status).toBe("CANCELLED");
    expect(response.body.data.cancelReason).toBe("Passenger changed plans");
  });

  it("returns a structured 409 for an invalid state transition", async () => {
    const created = await createTrip(app);
    const response = await request(app)
      .post(`/api/trips/${created.id}/start`)
      .set("X-Correlation-Id", randomUUID());

    expect(response.status).toBe(409);
    expect(response.body).toEqual({
      error: {
        code: "INVALID_STATE_TRANSITION",
        message: "Cannot transition trip from REQUESTED to IN_PROGRESS",
      },
    });
  });

  it("returns 400 for invalid create input", async () => {
    const response = await request(app)
      .post("/api/trips")
      .set(requestHeaders())
      .send({ passengerId: "not-a-uuid" });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe("VALIDATION_ERROR");
  });

  it("requires an idempotency key when creating a trip", async () => {
    const response = await request(app)
      .post("/api/trips")
      .set("X-Correlation-Id", randomUUID())
      .send({
        passengerId: randomUUID(),
        pickup: { lat: 10.7769, lng: 106.7009 },
        dropoff: { lat: 10.8231, lng: 106.6297 },
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe("VALIDATION_ERROR");
  });

  it("lists trips using validated query filters", async () => {
    const created = await createTrip(app);
    const response = await request(app)
      .get(`/api/trips?passengerId=${created.passengerId}&status=REQUESTED`)
      .set("X-Correlation-Id", randomUUID());

    expect(response.status).toBe(200);
    expect(response.body.data).toHaveLength(1);
    expect(response.body.data[0].id).toBe(created.id);
  });

  it("requires the correlation header on reads", async () => {
    const created = await createTrip(app);
    const response = await request(app).get(`/api/trips/${created.id}`);

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe("VALIDATION_ERROR");
  });
});
