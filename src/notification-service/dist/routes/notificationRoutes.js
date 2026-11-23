"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createNotificationRoutes = createNotificationRoutes;
const express_1 = require("express");
const notificationController_1 = require("../controllers/notificationController");
/** Create routes backed by the supplied repository. */
function createNotificationRoutes(repository) {
    const router = (0, express_1.Router)();
    const controller = new notificationController_1.NotificationController(repository);
    router.get('/', controller.list);
    router.post('/test-send', controller.testSend);
    router.get('/:id', controller.getById);
    return router;
}
