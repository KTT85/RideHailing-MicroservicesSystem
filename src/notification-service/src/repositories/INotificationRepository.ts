import { Notification } from '../types/notification';

export interface INotificationRepository {
  /** Create a pending notification. */
  create(input: Omit<Notification, 'id' | 'status' | 'createdAt' | 'sentAt'>): Promise<Notification>;
  /** Record successful delivery. */
  markSent(id: string): Promise<void>;
  /** Record failed delivery. */
  markFailed(id: string): Promise<void>;
  /** Return notifications matching every supplied filter. */
  list(filter: { recipientId?: string; tripId?: string }): Promise<Notification[]>;
}
