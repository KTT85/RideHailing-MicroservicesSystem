import type { ErrorRequestHandler, NextFunction, Request, Response } from "express";
import type { Logger } from "pino";
import { ZodError } from "zod";
import type { ParsedQs } from "qs";
import type { ParamsDictionary } from "express-serve-static-core";

/** Request-scoped data available to controllers and middleware. */
export interface ResponseLocals extends Record<string, unknown> {
  correlationId: string;
  logger: Logger;
}

/** An expected API error with a safe status and stable response code. */
export class TripServiceError extends Error {
  /** Creates an expected API error. */
  constructor(
    public readonly statusCode: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "TripServiceError";
  }
}

function isBodyParserError(error: unknown): error is { type: string; status: number } {
  if (typeof error !== "object" || error === null) return false;
  if (!("type" in error) || !("status" in error)) return false;
  return typeof error.type === "string" && typeof error.status === "number";
}

type TripServiceRequest = Request<ParamsDictionary, unknown, unknown, ParsedQs, ResponseLocals>;

/** Converts application, validation, and unexpected errors to the API error format. */
export const errorHandler: ErrorRequestHandler<
  ParamsDictionary,
  unknown,
  unknown,
  ParsedQs,
  ResponseLocals
> = (
  error: unknown,
  _request: TripServiceRequest,
  response: Response<unknown, ResponseLocals>,
  _next: NextFunction,
): void => {
  let statusCode = 500;
  let code = "INTERNAL_SERVER_ERROR";
  let message = "An unexpected error occurred";

  if (error instanceof TripServiceError) {
    statusCode = error.statusCode;
    code = error.code;
    message = error.message;
  } else if (error instanceof ZodError) {
    statusCode = 400;
    code = "VALIDATION_ERROR";
    message = "Request validation failed";
  } else if (isBodyParserError(error) && error.type === "entity.parse.failed") {
    statusCode = 400;
    code = "INVALID_JSON";
    message = "Request body must contain valid JSON";
  }

  const logFields = {
    code,
    statusCode,
    error: error instanceof Error ? { message: error.message, stack: error.stack } : String(error),
  };
  if (statusCode >= 500) {
    response.locals.logger.error(logFields, "Trip request failed");
  } else {
    response.locals.logger.warn(logFields, "Trip request rejected");
  }

  response.status(statusCode).json({ error: { code, message } });
};
