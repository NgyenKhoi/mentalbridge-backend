import { createApp } from "./app.js";

const shutdownTimeoutMilliseconds = 10_000;
const port = Number.parseInt(process.env.PORT ?? "3000", 10);

if (!Number.isInteger(port) || port < 1 || port > 65_535) {
  throw new Error("PORT must be an integer between 1 and 65535");
}

const server = createApp().listen(port);

let isShuttingDown = false;

const shutdown = (): void => {
  if (isShuttingDown) {
    return;
  }

  isShuttingDown = true;

  const forceShutdownTimer = setTimeout(() => {
    server.closeAllConnections();
    process.exitCode = 1;
  }, shutdownTimeoutMilliseconds);

  forceShutdownTimer.unref();

  server.close((error) => {
    clearTimeout(forceShutdownTimer);

    if (error) {
      process.exitCode = 1;
    }
  });
};

process.once("SIGINT", shutdown);
process.once("SIGTERM", shutdown);