import pino, { type Logger } from "pino";

import type { ServiceConfiguration } from "../configuration/configuration.js";

export const createLogger = (configuration: ServiceConfiguration): Logger =>
  pino({
    level: configuration.LOG_LEVEL,
    base: {
      service: configuration.SERVICE_NAME,
      environment: configuration.NODE_ENV,
    },
    redact: {
      paths: [
        "req.headers.authorization",
        "req.headers.cookie",
        "req.headers['x-mentalbridge-service-token']",
        "req.headers['x-mentalbridge-voice-care-token']",
        "req.headers['x-mentalbridge-voice-entitlement-token']",
        "req.headers['x-mentalbridge-voice-worker-token']",
        "req.headers['set-cookie']",
        "res.headers['set-cookie']",
      ],
      censor: "[redacted]",
    },
  });
