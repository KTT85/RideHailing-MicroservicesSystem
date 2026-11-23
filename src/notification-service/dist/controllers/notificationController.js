"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.NotificationController = void 0;
const errorHandler_1 = require("../middleware/errorHandler");
const notificationSchemas_1 = require("../validators/notificationSchemas");
class NotificationController {
    repository;
    /** Bind the shared repository contract. */
    constructor(repository) {
        this.repository = repository;
    }
    /** List notification history with optional recipient and trip filters. */
    list = async (req, res, next) => {
        try {
            res.json(await this.repository.list(notificationSchemas_1.listNotificationsSchema.parse(req.query)));
        }
        catch (error) {
            next(error);
        }
    };
    /** Retrieve a notification without extending the repository contract. */
    getById = async (req, res, next) => {
        try {
            const { id } = notificationSchemas_1.notificationIdSchema.parse(req.params);
            const notification = (await this.repository.list({})).find(item => item.id === id);
            if (!notification)
                throw new errorHandler_1.HttpError(404, 'NOTIFICATION_NOT_FOUND', 'Notification not found');
            res.json(notification);
        }
        catch (error) {
            next(error);
        }
    };
    /** Simulate delivery and return the persisted SENT notification. */
    testSend = async (req, res, next) => {
        try {
            const input = notificationSchemas_1.testSendSchema.parse(req.body);
            const notification = await this.repository.create({ ...input, eventType: 'notification.test', tripId: null });
            await this.repository.markSent(notification.id);
            const sent = (await this.repository.list({ recipientId: input.recipientId }))
                .find(item => item.id === notification.id);
            if (!sent)
                throw new Error('Created notification missing');
            res.status(201).json(sent);
        }
        catch (error) {
            next(error);
        }
    };
}
exports.NotificationController = NotificationController;
