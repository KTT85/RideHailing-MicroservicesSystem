import { Router, type Request, type RequestHandler } from "express";
import { randomUUID, timingSafeEqual } from "node:crypto";
import type { PrismaClient } from "@prisma/client";
import { z } from "zod";
import { TripServiceError } from "../middleware/errorHandler";
import { changeRide, createRide, demoFaults, readRide, rideActions, rideInput, rideView, uuid, type RideActor } from "../services/rideWorkflow";

export function demoFailpointsEnabled(): boolean {
  return process.env.DS03_ENABLE_DEMO_FAILPOINTS === "true" && (process.env.DS03_DEMO_FAILPOINT_TOKEN?.length ?? 0) >= 32;
}

function requireFailpointToken(request: Request): void {
  const configured = Buffer.from(process.env.DS03_DEMO_FAILPOINT_TOKEN ?? "");
  const supplied = Buffer.from(request.header("X-Demo-Failpoint-Token") ?? "");
  if (!demoFailpointsEnabled() || configured.length !== supplied.length || !timingSafeEqual(configured, supplied)) {
    throw new TripServiceError(403, "DEMO_FAILPOINT_DISABLED", "Demo failpoint is unavailable");
  }
}

async function profile(base: string, path: string, authorization: string): Promise<unknown> {
  try {
    const response = await fetch(new URL(path, base), {
      headers: { Authorization: authorization }, signal: AbortSignal.timeout(2500), redirect: "error",
    });
    if ([401, 403, 404].includes(response.status)) {
      throw new TripServiceError(response.status, "AUTH_REQUIRED", "Authentication or service profile is required");
    }
    if (!response.ok) throw new Error("Identity lookup failed");
    return await response.json();
  } catch (error) {
    if (error instanceof TripServiceError) throw error;
    throw new TripServiceError(503, "IDENTITY_UNAVAILABLE", "Identity service is unavailable");
  }
}

async function authenticate(request: Request): Promise<RideActor> {
  const authorization = request.header("Authorization");
  if (!authorization?.startsWith("Bearer ")) {
    throw new TripServiceError(401, "AUTH_REQUIRED", "Bearer token is required");
  }
  const account = z.object({ id: uuid, role: z.enum(["PASSENGER", "DRIVER"]) }).safeParse(
    await profile(process.env.USER_SERVICE_URL ?? "http://localhost:8081", "/users/me", authorization));
  if (!account.success) throw new TripServiceError(403, "RIDE_ROLE_REQUIRED", "Account role cannot access rides");
  if (account.data.role === "PASSENGER") return { role: "PASSENGER", userId: account.data.id };

  const driver = z.object({ driverId: uuid, userId: uuid }).safeParse(
    await profile(process.env.DRIVER_SERVICE_URL ?? "http://localhost:8082", "/drivers/me", authorization));
  if (!driver.success || driver.data.userId !== account.data.id) {
    throw new TripServiceError(403, "DRIVER_PROFILE_REQUIRED", "Driver profile is required");
  }
  return { role: "DRIVER", driverId: driver.data.driverId };
}

function endpoint(handler: (request: Request, actor: RideActor) => Promise<{ status?: number; data: unknown }>): RequestHandler {
  return (request, response, next) => {
    void authenticate(request).then(actor => handler(request, actor)).then(({ status = 200, data }) => {
      response.status(status).json({ data });
    }).catch(next);
  };
}

export function createRideRoutes(db: PrismaClient): Router {
  const router = Router();
  router.get(["/actuator/health", "/api/rides/health"], (_request, response) => {
    void db.$queryRaw`SELECT 1`.then(
      () => response.json({ status: "UP", rideWorkflow: true, demoFailpoints: demoFailpointsEnabled() }),
      () => response.status(503).json({ status: "DOWN", rideWorkflow: false }),
    );
  });
  router.post(["/trips", "/api/rides"], endpoint(async (request, actor) => {
    if (actor.role !== "PASSENGER") throw new TripServiceError(403, "PASSENGER_REQUIRED", "Passenger account is required");
    const key = z.string().trim().min(1).max(128).parse(request.header("Idempotency-Key"));
    const suppliedCorrelationId = request.header("X-Correlation-Id");
    const correlationId = suppliedCorrelationId === undefined ? randomUUID() : uuid.parse(suppliedCorrelationId);
    const requestedFault = request.header("X-Demo-Failpoint");
    if (requestedFault) requireFailpointToken(request);
    const demoFault = requestedFault ? demoFaults.parse(requestedFault) : undefined;
    const { ride, created } = await createRide(db, actor.userId, key, correlationId, rideInput.parse(request.body), demoFault);
    return { status: created ? 201 : 200, data: rideView(ride) };
  }));
  router.get(["/trips/:id", "/api/rides/:id"], endpoint(async (request, actor) => ({
    data: rideView(await readRide(db, uuid.parse(request.params.id), actor)),
  })));
  router.post(["/trips/:id/:action", "/api/rides/:id/:action"], endpoint(async (request, actor) => {
    const action = request.params.action === "cash-payment-confirmation" ? "confirm-cash" : request.params.action;
    return { data: rideView(await changeRide(db, uuid.parse(request.params.id), actor, rideActions.parse(action))) };
  }));
  return router;
}
