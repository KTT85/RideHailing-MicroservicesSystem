export type NotificationChannel = 'PUSH' | 'SMS' | 'EMAIL';
export type NotificationStatus = 'PENDING' | 'SENT' | 'FAILED';

export interface Notification {
  id: string;
  recipientId: string;
  recipientRole: 'PASSENGER' | 'DRIVER';
  channel: NotificationChannel;
  eventType: string;
  tripId: string | null;
  title: string;
  body: string;
  status: NotificationStatus;
  createdAt: string;
  sentAt: string | null;
}
