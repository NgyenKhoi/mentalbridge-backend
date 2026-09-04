import type { IncomingMessage, ServerResponse } from 'node:http';

import { Inject, Injectable, type NestMiddleware } from '@nestjs/common';
import type { Logger } from 'pino';
import { pinoHttp } from 'pino-http';

import { LOGGER_TOKEN } from '../shared/tokens.js';
import { normalizeCorrelationId } from './correlation-id.js';

@Injectable()
export class RequestLoggingMiddleware implements NestMiddleware {
  private readonly httpLogger: ReturnType<typeof pinoHttp>;

  constructor(@Inject(LOGGER_TOKEN) logger: Logger) {
    this.httpLogger = pinoHttp({
      logger,
      customProps: (request) => ({ correlationId: (request as { id?: string }).id }),
    });
  }

  use(request: IncomingMessage, response: ServerResponse, next: () => void): void {
    const correlationId = normalizeCorrelationId(request.headers['x-correlation-id']);
    (request as IncomingMessage & { id: string }).id = correlationId;
    response.setHeader('x-correlation-id', correlationId);
    this.httpLogger(request, response, next);
  }
}
