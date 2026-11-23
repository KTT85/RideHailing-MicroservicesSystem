"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.createApp = createApp;
const node_crypto_1 = require("node:crypto");
const express_1 = __importDefault(require("express"));
const notificationRoutes_1 = require("./routes/notificationRoutes");
const InMemoryNotificationRepository_1 = require("./repositories/InMemoryNotificationRepository");
const errorHandler_1 = require("./middleware/errorHandler");
/** Build an isolated application; callers may inject a Prisma repository later. */
function createApp(repository = new InMemoryNotificationRepository_1.InMemoryNotificationRepository()) {
    const app = (0, express_1.default)();
    app.use((req, res, next) => {
        const supplied = req.get('x-correlation-id');
        const correlationId = supplied && /^[A-Za-z0-9._:-]{1,128}$/.test(supplied) ? supplied : (0, node_crypto_1.randomUUID)();
        res.setHeader('x-correlation-id', correlationId);
        res.on('finish', () => errorHandler_1.LOGGER.info({ correlationId, method: req.method, status: res.statusCode }, 'Request completed'));
        next();
    });
    app.use(express_1.default.json({ limit: '100kb' }));
    app.use('/api/notifications', (0, notificationRoutes_1.createNotificationRoutes)(repository));
    app.use((_req, _res, next) => next(new errorHandler_1.HttpError(404, 'ROUTE_NOT_FOUND', 'Route not found')));
    app.use(errorHandler_1.errorHandler);
    return app;
}
exports.default = createApp;
