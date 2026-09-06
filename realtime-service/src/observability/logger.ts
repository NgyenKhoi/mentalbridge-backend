import pino, { type DestinationStream, type Logger } from 'pino';

import type { ServiceConfiguration } from '../configuration/configuration.js';

export const createLogger = (
  configuration: ServiceConfiguration,
  destination?: DestinationStream,
): Logger =>
  pino(
    {
      level: configuration.LOG_LEVEL,
      base: { service: configuration.SERVICE_NAME, environment: configuration.NODE_ENV },
      redact: {
        paths: [
          'req.headers.authorization',
          'req.headers.cookie',
          'handshake.auth',
          'accessToken',
          'content',
          'payload.content',
          'bodyCiphertext',
        ],
        censor: '[redacted]',
      },
    },
    destination,
  );
