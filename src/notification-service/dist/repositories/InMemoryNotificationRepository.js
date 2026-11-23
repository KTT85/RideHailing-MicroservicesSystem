"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.InMemoryNotificationRepository = void 0;
const node_crypto_1 = require("node:crypto");
const errorHandler_1 = require("../middleware/errorHandler");
class InMemoryNotificationRepository {
    notifications = new Map();
    /** Store a new pending notification and return a detached copy. */
    async create(input) {
        const notification = {
            ...input, id: (0, node_crypto_1.randomUUID)(), status: 'PENDING',
            createdAt: new Date().toISOString(), sentAt: null,
        };
        this.notifications.set(notification.id, notification);
        return { ...notification };
    }
    /** Mark an existing notification sent. */
    async markSent(id) {
        Object.assign(this.requireNotification(id), { status: 'SENT', sentAt: new Date().toISOString() });
    }
    /** Mark an existing notification failed. */
    async markFailed(id) {
        Object.assign(this.requireNotification(id), { status: 'FAILED', sentAt: null });
    }
    /** Return detached copies matching the supplied filters. */
    async list(filter) {
        return [...this.notifications.values()]
            .filter(item => (filter.recipientId === undefined || item.recipientId === filter.recipientId)
            && (filter.tripId === undefined || item.tripId === filter.tripId))
            .map(item => ({ ...item }));
    }
    requireNotification(id) {
        const notification = this.notifications.get(id);
        if (!notification)
            throw new errorHandler_1.HttpError(404, 'NOTIFICATION_NOT_FOUND', 'Notification not found');
        return notification;
    }
}
exports.InMemoryNotificationRepository = InMemoryNotificationRepository;
