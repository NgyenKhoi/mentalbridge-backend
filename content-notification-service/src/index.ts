import app from './app.js';
import logger from './shared/logger.js';
import { config } from './shared/config.js';
import { pool } from './infrastructure/database/db.js';

const server = app.listen(config.PORT, () => {
  logger.info({
    event: 'server_started',
    port: config.PORT,
    service: 'content-notification-service',
    nodeEnv: config.NODE_ENV,
  });
});

// Graceful shutdown — do NOT auto-migrate on startup
const shutdown = (signal: string): void => {
  logger.info({ event: 'shutdown_initiated', signal });

  server.close(() => {
    logger.info({ event: 'http_server_closed' });

    pool
      .end()
      .then(() => {
        logger.info({ event: 'db_pool_closed' });
        process.exit(0);
      })
      .catch((err: unknown) => {
        logger.error({ event: 'shutdown_error', error: err });
        process.exit(1);
      });
  });

  setTimeout(() => {
    logger.error({ event: 'shutdown_timeout' });
    process.exit(1);
  }, 10_000);
};

process.on('SIGTERM', () => {
  shutdown('SIGTERM');
});
process.on('SIGINT', () => {
  shutdown('SIGINT');
});
