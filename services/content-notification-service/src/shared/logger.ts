import pino from 'pino';
import { config } from './config';

const logger = pino({
  level: process.env.LOG_LEVEL ?? 'info',
  redact: {
    paths: [
      'DB_PASSWORD',
      'password',
      'token',
      'secret',
      'req.headers.authorization',
      'req.headers["x-api-key"]',
      'authorization',
      'JWT_PUBLIC_KEY',
    ],
    censor: '[REDACTED]',
  },
  base: {
    service: 'content-notification-service',
    env: config.NODE_ENV,
  },
  formatters: {
    level: (label: string) => {
      return { level: label };
    },
  },
});

export default logger;
