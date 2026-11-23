import { NextFunction, Request, Response } from 'express';
import { INotificationRepository } from '../repositories/INotificationRepository';
import { HttpError } from '../middleware/errorHandler';
import { listNotificationsSchema, notificationIdSchema, testSendSchema } from '../validators/notificationSchemas';

export class NotificationController {
  /** Bind the shared repository contract. */
  constructor(private readonly repository: INotificationRepository) {}

  /** List notification history with optional recipient and trip filters. */
  list = async (req: Request, res: Response, next: NextFunction): Promise<void> => {
    try {
      res.json(await this.repository.list(listNotificationsSchema.parse(req.query)));
    } catch (error) { next(error); }
  };

  /** Retrieve a notification without extending the repository contract. */
  getById = async (req: Request, res: Response, next: NextFunction): Promise<void> => {
    try {
      const { id } = notificationIdSchema.parse(req.params);
      const notification = (await this.repository.list({})).find(item => item.id === id);
      if (!notification) throw new HttpError(404, 'NOTIFICATION_NOT_FOUND', 'Notification not found');
      res.json(notification);
    } catch (error) { next(error); }
  };

  /** Simulate delivery and return the persisted SENT notification. */
  testSend = async (req: Request, res: Response, next: NextFunction): Promise<void> => {
    try {
      const input = testSendSchema.parse(req.body);
      const notification = await this.repository.create({ ...input, eventType: 'notification.test', tripId: null });
      await this.repository.markSent(notification.id);
      const sent = (await this.repository.list({ recipientId: input.recipientId }))
        .find(item => item.id === notification.id);
      if (!sent) throw new Error('Created notification missing');
      res.status(201).json(sent);
    } catch (error) { next(error); }
  };
}
