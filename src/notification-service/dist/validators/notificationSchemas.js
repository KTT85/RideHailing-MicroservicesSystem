"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.testSendSchema = exports.notificationIdSchema = exports.listNotificationsSchema = void 0;
const zod_1 = require("zod");
const NON_EMPTY_STRING = zod_1.z.string().trim().min(1);
exports.listNotificationsSchema = zod_1.z.object({
    recipientId: NON_EMPTY_STRING.optional(),
    tripId: NON_EMPTY_STRING.optional(),
}).strict();
exports.notificationIdSchema = zod_1.z.object({ id: NON_EMPTY_STRING }).strict();
exports.testSendSchema = zod_1.z.object({
    recipientId: NON_EMPTY_STRING,
    recipientRole: zod_1.z.enum(['PASSENGER', 'DRIVER']),
    channel: zod_1.z.enum(['PUSH', 'SMS', 'EMAIL']),
    title: NON_EMPTY_STRING,
    body: NON_EMPTY_STRING,
}).strict();
