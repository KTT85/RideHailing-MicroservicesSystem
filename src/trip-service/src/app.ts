import { randomUUID } from "node:crypto";
import express, { type RequestHandler } from "express";
import pino, { type Logger } from "pino";
import type { ParsedQs } from "qs";
import type { ParamsDictionary } from "express-serve-static-core";
import { TripController } from "./controllers/tripController";
import { errorHandler, type ResponseLocals } from "./middleware/errorHandler";
import { NoopEventPublisher } from "./messaging/NoopEventPublisher";
import type { IEventPublisher } from "./messaging/IEventPublisher";
import { InMemoryTripRepository } from "./repositories/InMemoryTripRepository";
import type { ITripRepository } from "./repositories/ITripRepository";
import { createTripRoutes } from "./routes/tripRoutes";
import { TripService } from "./services/tripService";

const SERVICE_NAME = "trip-service";

/** Optional dependencies for an isolated app instance, such as in tests. */
export interface TripServiceDependencies {
  service?: TripService;
  repository?: ITripRepository;
  eventPublisher?: IEventPublisher;
  logger?: Logger;
}

/** Creates a JSON logger with the service field required by the API contract. */
export function createServiceLogger(): Logger {
  return pino({ level: process.env.LOG_LEVEL ?? "info", base: { service: SERVICE_NAME } });
}

/** Builds an Express application with in-memory and no-op defaults. */
export function createApp(dependencies: TripServiceDependencies = {}): express.Express {
  const logger = dependencies.logger ?? createServiceLogger();
  const repository = dependencies.repository ?? new InMemoryTripRepository();
  const eventPublisher = dependencies.eventPublisher ?? new NoopEventPublisher(logger.child({ service: SERVICE_NAME }));
  const tripController = new TripController(dependencies.service ?? new TripService(repository, eventPublisher));
  const app = express();

  const requestContext: RequestHandler<
    ParamsDictionary,
    unknown,
    unknown,
    ParsedQs,
    ResponseLocals
  > = (request, response, next): void => {
    const suppliedCorrelationId = request.header("X-Correlation-Id")?.trim();
    const correlationId = suppliedCorrelationId === undefined || suppliedCorrelationId.length === 0
      ? randomUUID()
      : suppliedCorrelationId;
    response.locals.correlationId = correlationId;
    response.locals.logger = logger.child({ correlationId, service: SERVICE_NAME });
    response.on("finish", () => {
      response.locals.logger.info(
        { method: request.method, path: request.path, statusCode: response.statusCode },
        "Trip request completed",
      );
    });
    next();
  };

  const routeNotFound: RequestHandler<
    ParamsDictionary,
    unknown,
    unknown,
    ParsedQs,
    ResponseLocals
  > = (_request, response): void => {
    response.status(404).json({ error: { code: "ROUTE_NOT_FOUND", message: "Route was not found" } });
  };

  app.use(requestContext);
  app.use(express.json());
  app.use("/api/trips", createTripRoutes(tripController));
  app.use(routeNotFound);
  app.use(errorHandler);
  return app;
}

/** Writes a readiness response using the configured JSON logger. */
export function logServerReady(logger: Logger, port: number): void {
  logger.info({ port }, "Trip Service is listening");
}

