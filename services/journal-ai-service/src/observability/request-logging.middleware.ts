import type { IncomingMessage, ServerResponse } from "node:http";

import { Inject, Injectable, type NestMiddleware } from "@nestjs/common";
import { pinoHttp } from "pino-http";
import type { Logger } from "pino";

import {
  getCorrelationId,
  setCorrelationIdHeader,
} from "./correlation-id.js";
import { LOGGER_TOKEN } from "./tokens.js";

@Injectable()
export class RequestLoggingMiddleware implements NestMiddleware {
  constructor(@Inject(LOGGER_TOKEN) private readonly logger: Logger) {}

  use(
    request: IncomingMessage,
    response: ServerResponse,
    next: () => void,
  ): void {
    const correlationId = getCorrelationId(request);
    setCorrelationIdHeader(response, correlationId);

    pinoHttp({
      logger: this.logger,
      genReqId: () => correlationId,
      customProps: () => ({
        correlationId,
      }),
    })(request, response, next);
  }
}
