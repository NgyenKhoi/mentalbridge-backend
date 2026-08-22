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
  private readonly httpLogger: ReturnType<typeof pinoHttp>;

  constructor(@Inject(LOGGER_TOKEN) logger: Logger) {
    this.httpLogger = pinoHttp({
      logger,
      genReqId: (request) =>
        (request as { id?: string }).id ??
        getCorrelationId(request as IncomingMessage),
      customProps: (request) => ({
        correlationId: (request as { id?: string }).id,
      }),
    });
  }

  use(
    request: IncomingMessage,
    response: ServerResponse,
    next: () => void,
  ): void {
    const correlationId = getCorrelationId(request);
    setCorrelationIdHeader(response, correlationId);
    const identifiedRequest = request as IncomingMessage & { id?: string };
    identifiedRequest.id = correlationId;

    this.httpLogger(identifiedRequest, response, next);
  }
}
