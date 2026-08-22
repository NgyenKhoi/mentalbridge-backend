import pino, { type Logger } from 'pino';

import type { ServiceConfiguration } from '../configuration/configuration.js';

export const createLogger = (configuration: ServiceConfiguration): Logger =>
  pino({
    level: configuration.LOG_LEVEL,
    redact: {
      paths: ['password', 'token', 'secret', 'req.headers.authorization', 'req.headers.cookie'],
      censor: '[REDACTED]',
    },
    base: {
      service: configuration.SERVICE_NAME,
      environment: configuration.NODE_ENV,
    },
  });
