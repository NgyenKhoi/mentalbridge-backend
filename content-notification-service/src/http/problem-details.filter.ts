import { Catch, HttpException, type ArgumentsHost, type ExceptionFilter } from '@nestjs/common';
import type { Request, Response } from 'express';
import type { Logger } from 'pino';
import { randomUUID } from 'node:crypto';

@Catch()
export class ProblemDetailsFilter implements ExceptionFilter {
  constructor(private readonly logger: Logger) {}

  catch(exception: unknown, host: ArgumentsHost): void {
    const request = host.switchToHttp().getRequest<Request>();
    const response = host.switchToHttp().getResponse<Response>();
    const status = exception instanceof HttpException ? exception.getStatus() : 500;
    const correlationId = this.correlationId(request);
    const code = this.code(status);

    if (status >= 500) {
      this.logger.error({ event: 'request_failed', status, code, correlationId });
    }

    response
      .status(status)
      .type('application/problem+json')
      .setHeader('x-correlation-id', correlationId)
      .json({
        type: `https://mentalbridge.io/errors/${code}`,
        title: this.title(status),
        status,
        code,
        correlationId,
      });
  }

  private correlationId(request: Request): string {
    const supplied = request.header('x-correlation-id');
    return supplied && supplied.length <= 128 ? supplied : randomUUID();
  }

  private code(status: number): string {
    if (status === 404) return 'RESOURCE_NOT_FOUND';
    if (status === 503) return 'DEPENDENCY_UNAVAILABLE';
    return status >= 500 ? 'INTERNAL_ERROR' : 'REQUEST_REJECTED';
  }

  private title(status: number): string {
    if (status === 404) return 'Resource not found';
    if (status === 503) return 'Dependency unavailable';
    return status >= 500 ? 'An unexpected error occurred' : 'Request rejected';
  }
}
