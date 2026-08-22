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
        "req.headers['set-cookie']",
        "res.headers['set-cookie']",
      ],
      censor: "[redacted]",
    },
  });
