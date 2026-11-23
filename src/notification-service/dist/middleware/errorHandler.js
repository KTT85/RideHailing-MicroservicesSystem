"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.errorHandler = exports.HttpError = exports.LOGGER = void 0;
const zod_1 = require("zod");
const pino_1 = __importDefault(require("pino"));
exports.LOGGER = (0, pino_1.default)({ base: { service: 'notification-service' }, mixin: () => ({ correlationId: 'system' }) });
class HttpError extends Error {
    status;
    code;
    /** Describe a safe error response. */
    constructor(status, code, message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
exports.HttpError = HttpError;
/** Convert validation, JSON parsing and application errors to a common envelope. */
const errorHandler = (error, _req, res, _next) => {
    let status = 500;
    let code = 'INTERNAL_SERVER_ERROR';
    let message = 'Internal server error';
    if (error instanceof zod_1.ZodError) {
        status = 400;
        code = 'VALIDATION_ERROR';
        message = error.issues.map(issue => `${issue.path.join('.') || 'input'}: ${issue.message}`).join('; ');
    }
    else if (error instanceof HttpError) {
        ({ status, code, message } = error);
    }
    else if (typeof error === 'object' && error !== null && 'type' in error) {
        if (error.type === 'entity.parse.failed') {
            status = 400;
            code = 'INVALID_JSON';
            message = 'Request body must be valid JSON';
        }
        else if (error.type === 'entity.too.large') {
            status = 413;
            code = 'PAYLOAD_TOO_LARGE';
            message = 'Request body is too large';
        }
    }
    exports.LOGGER.error({ correlationId: res.getHeader('x-correlation-id'), code, status }, 'Request failed');
    res.status(status).json({ error: { code, message } });
};
exports.errorHandler = errorHandler;
