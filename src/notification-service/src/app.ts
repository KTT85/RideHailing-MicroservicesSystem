import { randomUUID } from 'node:crypto';
import express, { Express } from 'express';
import { createNotificationRoutes } from './routes/notificationRoutes';
import { InMemoryNotificationRepository } from './repositories/InMemoryNotificationRepository';
import { INotificationRepository } from './repositories/INotificationRepository';
import { errorHandler, HttpError, LOGGER } from './middleware/errorHandler';

/** Build an isolated application; callers may inject a Prisma repository later. */
export function createApp(repository: INotificationRepository = new InMemoryNotificationRepository()): Express {
  const app = express();
  app.use((req, res, next) => {
    const supplied = req.get('x-correlation-id');
    const correlationId = supplied && /^[A-Za-z0-9._:-]{1,128}$/.test(supplied) ? supplied : randomUUID();
    res.setHeader('x-correlation-id', correlationId);
    res.on('finish', () => LOGGER.info({ correlationId, method: req.method, status: res.statusCode }, 'Request completed'));
    next();
  });
  app.use(express.json({ limit: '100kb' }));
  app.use('/api/notifications', createNotificationRoutes(repository));
  app.use((_req, _res, next) => next(new HttpError(404, 'ROUTE_NOT_FOUND', 'Route not found')));
  app.use(errorHandler);
  return app;
}

export default createApp;
