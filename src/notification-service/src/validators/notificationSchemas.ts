import { z } from 'zod';

const NON_EMPTY_STRING = z.string().trim().min(1);
export const listNotificationsSchema = z.object({
  recipientId: NON_EMPTY_STRING.optional(),
  tripId: NON_EMPTY_STRING.optional(),
}).strict();
export const notificationIdSchema = z.object({ id: NON_EMPTY_STRING }).strict();
export const testSendSchema = z.object({
  recipientId: NON_EMPTY_STRING,
  recipientRole: z.enum(['PASSENGER', 'DRIVER']),
  channel: z.enum(['PUSH', 'SMS', 'EMAIL']),
  title: NON_EMPTY_STRING,
  body: NON_EMPTY_STRING,
}).strict();
