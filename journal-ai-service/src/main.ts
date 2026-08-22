import { loadConfiguration } from "./configuration/configuration.js";
import { createApplication } from "./application.js";

const shutdownTimeoutMilliseconds = 10_000;
const configuration = loadConfiguration();

const app = await createApplication(configuration);
await app.listen(configuration.PORT);

let isShuttingDown = false;

const shutdown = (): void => {
  if (isShuttingDown) {
    return;
  }

  isShuttingDown = true;

  const forceShutdownTimer = setTimeout(() => {
    process.exit(1);
  }, shutdownTimeoutMilliseconds);

  forceShutdownTimer.unref();

  void app
    .close()
    .catch(() => {
      process.exitCode = 1;
    })
    .finally(() => {
      clearTimeout(forceShutdownTimer);
    });
};

process.once("SIGINT", shutdown);
process.once("SIGTERM", shutdown);
