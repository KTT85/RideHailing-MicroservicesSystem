"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const zod_1 = require("zod");
const app_1 = require("./app");
const errorHandler_1 = require("./middleware/errorHandler");
const PORT = zod_1.z.coerce.number().int().min(1).max(65535).parse(process.env.PORT ?? '3005');
const SERVER = (0, app_1.createApp)().listen(PORT, () => errorHandler_1.LOGGER.info({ port: PORT }, 'Notification REST API listening'));
SERVER.on('error', (error) => {
    errorHandler_1.LOGGER.error({ err: error }, 'Server failed');
    process.exitCode = 1;
});
