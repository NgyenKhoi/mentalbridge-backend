import 'dotenv/config';
import app from './app';
import logger from './shared/logger';
import { config } from './shared/config';
import { pool } from './infrastructure/database/db';

const server = app.listen(config.PORT, () => {
  logger.info({
    event: 'server_started',
    port: config.PORT,
    service: 'content-notification-service',
    nodeEnv: config.NODE_ENV,
  });
});

// Graceful shutdown
const shutdown = (signal: string) => {
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

  // Force shutdown after 10 seconds
  setTimeout(() => {
    logger.error({ event: 'shutdown_timeout' });
    process.exit(1);
  }, 10000);
};

process.on('SIGTERM', () => {
  shutdown('SIGTERM');
});
process.on('SIGINT', () => {
  shutdown('SIGINT');
});
