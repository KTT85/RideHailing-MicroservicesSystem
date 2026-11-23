import type { Server } from "node:http";
import { createApp, createServiceLogger, logServerReady } from "./app";

/** Starts the standalone Trip Service HTTP server. */
export function startServer(): Server {
  const port = Number(process.env.PORT ?? 3000);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error("PORT must be an integer from 1 to 65535");
  }

  const logger = createServiceLogger();
  const server = createApp({ logger }).listen(port, () => logServerReady(logger, port));
  return server;
}

if (require.main === module) {
  startServer();
}
