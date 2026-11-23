import type { NextFunction, Request, RequestHandler, Response } from "express";
import type { ParsedQs } from "qs";
import type { ParamsDictionary } from "express-serve-static-core";
import type { ResponseLocals } from "../middleware/errorHandler";
import { TripService } from "../services/tripService";
import {
  cancelTripBodySchema,
  completeTripBodySchema,
  correlationIdSchema,
  createTripSchema,
  driverActionBodySchema,
  emptyActionBodySchema,
  idempotencyKeySchema,
  listTripsQuerySchema,
  tripIdParamsSchema,
} from "../validators/tripSchemas";

type TypedTripRequest = Request<ParamsDictionary, unknown, unknown, ParsedQs, ResponseLocals>;
type TripRequestHandler = RequestHandler<ParamsDictionary, unknown, unknown, ParsedQs, ResponseLocals>;

function getCorrelationId(request: TypedTripRequest): string {
  return correlationIdSchema.parse(request.header("X-Correlation-Id"));
}

function getTripId(request: TypedTripRequest): string {
  return tripIdParamsSchema.parse(request.params).id;
}

function handleAsync(
  handler: (request: TypedTripRequest, response: Response<unknown, ResponseLocals>) => Promise<void>,
): TripRequestHandler {
  return (request: TypedTripRequest, response: Response<unknown, ResponseLocals>, next: NextFunction): void => {
    void handler(request, response).catch(next);
  };
}

/** Validates REST requests and delegates trip operations to TripService. */
export class TripController {
  /** Creates a controller for the supplied trip service. */
  constructor(private readonly tripService: TripService) {}

  /** Registers a new trip or returns the trip saved for the idempotency key. */
  readonly create: TripRequestHandler = handleAsync(async (request, response) => {
    const correlationId = getCorrelationId(request);
    const idempotencyKey = idempotencyKeySchema.parse(request.header("Idempotency-Key"));
    const body = createTripSchema.parse(request.body);
    const trip = await this.tripService.createTrip({ ...body, idempotencyKey }, { correlationId });
    response.status(201).json({ data: trip });
  });

  /** Returns one trip by UUID. */
  readonly get: TripRequestHandler = handleAsync(async (request, response) => {
    getCorrelationId(request);
    const trip = await this.tripService.getTrip(getTripId(request));
    response.status(200).json({ data: trip });
  });

  /** Returns trips filtered by passenger, driver, and status. */
  readonly list: TripRequestHandler = handleAsync(async (request, response) => {
    getCorrelationId(request);
    const filter = listTripsQuerySchema.parse(request.query);
    const trips = await this.tripService.listTrips(filter);
    response.status(200).json({ data: trips });
  });

  /** Accepts a trip after the mock matching and assignment transitions. */
  readonly accept: TripRequestHandler = handleAsync(async (request, response) => {
    const correlationId = getCorrelationId(request);
    const body = driverActionBodySchema.parse(request.body);
    const trip = await this.tripService.acceptTrip(getTripId(request), { correlationId }, body.driverId);
    response.status(200).json({ data: trip });
  });

  /** Rejects a driver-assigned trip. */
  readonly reject: TripRequestHandler = handleAsync(async (request, response) => {
    const correlationId = getCorrelationId(request);
    const body = driverActionBodySchema.parse(request.body);
    const trip = await this.tripService.rejectTrip(getTripId(request), { correlationId }, body.driverId);
    response.status(200).json({ data: trip });
  });

  /** Starts an accepted trip. */
  readonly start: TripRequestHandler = handleAsync(async (request, response) => {
    const correlationId = getCorrelationId(request);
    emptyActionBodySchema.parse(request.body);
    const trip = await this.tripService.startTrip(getTripId(request), { correlationId });
    response.status(200).json({ data: trip });
  });

  /** Completes an in-progress trip. */
  readonly complete: TripRequestHandler = handleAsync(async (request, response) => {
    const correlationId = getCorrelationId(request);
    const body = completeTripBodySchema.parse(request.body);
    const trip = await this.tripService.completeTrip(getTripId(request), { correlationId }, body.fare);
    response.status(200).json({ data: trip });
  });

  /** Cancels a trip before it starts, storing the optional reason. */
  readonly cancel: TripRequestHandler = handleAsync(async (request, response) => {
    const correlationId = getCorrelationId(request);
    const body = cancelTripBodySchema.parse(request.body);
    const trip = await this.tripService.cancelTrip(getTripId(request), { correlationId }, body.reason);
    response.status(200).json({ data: trip });
  });
}
