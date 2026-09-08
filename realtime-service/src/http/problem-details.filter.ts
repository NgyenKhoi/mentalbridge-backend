import { type ArgumentsHost, Catch, type ExceptionFilter, HttpException } from '@nestjs/common';

import { ApplicationException } from './application.exception.js';

interface ErrorRequest {
  readonly id?: string;
}

interface ErrorResponse {
  status(code: number): ErrorResponse;
  type(contentType: string): ErrorResponse;
  json(body: unknown): void;
}

const titles: Readonly<Record<number, string>> = {
  400: 'Request validation failed',
  401: 'Authentication is required',
  403: 'Access is denied',
  404: 'Resource was not found',
  409: 'The request conflicts with current state',
  429: 'The request rate limit was exceeded',
  503: 'A required dependency is unavailable',
};

@Catch()
export class ProblemDetailsFilter implements ExceptionFilter {
  catch(exception: unknown, host: ArgumentsHost): void {
    const http = host.switchToHttp();
    const request = http.getRequest<ErrorRequest>();
    const response = http.getResponse<ErrorResponse>();
    const status = exception instanceof HttpException ? exception.getStatus() : 500;
    const code =
      exception instanceof ApplicationException ? exception.code : this.defaultCode(status);
    response
      .status(status)
      .type('application/problem+json')
      .json({
        type: `https://mentalbridge.dev/problems/${code.toLowerCase().replaceAll('_', '-')}`,
        title: titles[status] ?? 'An internal error occurred',
        status,
        code,
        correlationId: request.id ?? 'unavailable',
      });
  }

  private defaultCode(status: number): string {
    if (status === 400) return 'VALIDATION_FAILED';
    if (status === 401) return 'AUTHENTICATION_REQUIRED';
    if (status === 403) return 'ACCESS_DENIED';
    if (status === 404) return 'RESOURCE_NOT_FOUND';
    if (status === 429) return 'RATE_LIMITED';
    if (status === 503) return 'DEPENDENCY_UNAVAILABLE';
    return 'INTERNAL_ERROR';
  }
}
