import { ErrorRequestHandler } from 'express';
import { ZodError } from 'zod';
import pino from 'pino';

export const LOGGER = pino({ base: { service: 'notification-service' }, mixin: () => ({ correlationId: 'system' }) });

export class HttpError extends Error {
  /** Describe a safe error response. */
  constructor(public readonly status: number, public readonly code: string, message: string) {
    super(message);
  }
}

/** Convert validation, JSON parsing and application errors to a common envelope. */
export const errorHandler: ErrorRequestHandler = (error: unknown, _req, res, _next) => {
  let status = 500;
  let code = 'INTERNAL_SERVER_ERROR';
  let message = 'Internal server error';
  if (error instanceof ZodError) {
    status = 400;
    code = 'VALIDATION_ERROR';
    message = error.issues.map(issue => `${issue.path.join('.') || 'input'}: ${issue.message}`).join('; ');
  } else if (error instanceof HttpError) {
    ({ status, code, message } = error);
  } else if (typeof error === 'object' && error !== null && 'type' in error) {
    if (error.type === 'entity.parse.failed') {
      status = 400; code = 'INVALID_JSON'; message = 'Request body must be valid JSON';
    } else if (error.type === 'entity.too.large') {
      status = 413; code = 'PAYLOAD_TOO_LARGE'; message = 'Request body is too large';
    }
  }
  LOGGER.error({ correlationId: res.getHeader('x-correlation-id'), code, status }, 'Request failed');
  res.status(status).json({ error: { code, message } });
};

