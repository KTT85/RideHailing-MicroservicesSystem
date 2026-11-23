import { Router } from 'express';
import { NotificationController } from '../controllers/notificationController';
import { INotificationRepository } from '../repositories/INotificationRepository';

/** Create routes backed by the supplied repository. */
export function createNotificationRoutes(repository: INotificationRepository): Router {
  const router = Router();
  const controller = new NotificationController(repository);
  router.get('/', controller.list);
  router.post('/test-send', controller.testSend);
  router.get('/:id', controller.getById);
  return router;
}
