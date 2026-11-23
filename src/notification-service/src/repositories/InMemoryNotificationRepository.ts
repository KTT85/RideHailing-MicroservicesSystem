import { randomUUID } from 'node:crypto';
import { Notification } from '../types/notification';
import { INotificationRepository } from './INotificationRepository';
import { HttpError } from '../middleware/errorHandler';

export class InMemoryNotificationRepository implements INotificationRepository {
  private readonly notifications = new Map<string, Notification>();

  /** Store a new pending notification and return a detached copy. */
  async create(input: Omit<Notification, 'id' | 'status' | 'createdAt' | 'sentAt'>): Promise<Notification> {
    const notification: Notification = {
      ...input, id: randomUUID(), status: 'PENDING',
      createdAt: new Date().toISOString(), sentAt: null,
    };
    this.notifications.set(notification.id, notification);
    return { ...notification };
  }

  /** Mark an existing notification sent. */
  async markSent(id: string): Promise<void> {
    Object.assign(this.requireNotification(id), { status: 'SENT', sentAt: new Date().toISOString() });
  }

  /** Mark an existing notification failed. */
  async markFailed(id: string): Promise<void> {
    Object.assign(this.requireNotification(id), { status: 'FAILED', sentAt: null });
  }

  /** Return detached copies matching the supplied filters. */
  async list(filter: { recipientId?: string; tripId?: string }): Promise<Notification[]> {
    return [...this.notifications.values()]
      .filter(item => (filter.recipientId === undefined || item.recipientId === filter.recipientId)
        && (filter.tripId === undefined || item.tripId === filter.tripId))
      .map(item => ({ ...item }));
  }

  private requireNotification(id: string): Notification {
    const notification = this.notifications.get(id);
    if (!notification) throw new HttpError(404, 'NOTIFICATION_NOT_FOUND', 'Notification not found');
    return notification;
  }
}
