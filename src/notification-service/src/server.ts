import { z } from 'zod';
import { createApp } from './app';
import { LOGGER } from './middleware/errorHandler';

const PORT = z.coerce.number().int().min(1).max(65535).parse(process.env.PORT ?? '3005');
const SERVER = createApp().listen(PORT, () => LOGGER.info({ port: PORT }, 'Notification REST API listening'));
SERVER.on('error', (error: Error) => {
  LOGGER.error({ err: error }, 'Server failed');
  process.exitCode = 1;
});
