import { createApplication } from './application.js';
import { loadConfiguration } from './configuration/configuration.js';

const configuration = loadConfiguration();
const app = await createApplication(configuration);
await app.listen(configuration.PORT);

let shuttingDown = false;

const shutdown = (): void => {
  if (shuttingDown) return;
  shuttingDown = true;
  const timer = setTimeout(() => process.exit(1), configuration.SHUTDOWN_TIMEOUT_MS);
  timer.unref();
  void app
    .close()
    .catch(() => {
      process.exitCode = 1;
    })
    .finally(() => {
      clearTimeout(timer);
    });
};

process.once('SIGINT', shutdown);
process.once('SIGTERM', shutdown);
