import pino from 'pino';
import { config } from './config.js';

const logger = pino({
  level: config.LOG_LEVEL,
  redact: {
    paths: [
      'DB_PASSWORD',
      'password',
      'token',
      'secret',
      'req.headers.authorization',
      'req.headers["x-api-key"]',
      'authorization',
    ],
    censor: '[REDACTED]',
  },
  base: {
    service: 'content-notification-service',
    env: config.NODE_ENV,
  },
  formatters: {
    level: (label: string) => ({ level: label }),
  },
});

export default logger;
