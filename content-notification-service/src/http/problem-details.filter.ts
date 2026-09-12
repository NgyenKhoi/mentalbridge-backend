import { Catch, HttpException, type ArgumentsHost, type ExceptionFilter } from '@nestjs/common';
import type { Request, Response } from 'express';
import type { Logger } from 'pino';
import { randomUUID } from 'node:crypto';
import { ResourceIdempotencyConflictError } from '../resources/resource.repository.js';

type SafeProblem = {
  readonly type: string;
  readonly title: string;
  readonly status: number;
  readonly code: string;
  readonly fieldViolations?: readonly { field: string; message: string }[];
};

@Catch()
export class ProblemDetailsFilter implements ExceptionFilter {
  constructor(private readonly logger: Logger) {}

  catch(exception: unknown, host: ArgumentsHost): void {
    const request = host.switchToHttp().getRequest<Request>();
    const response = host.switchToHttp().getResponse<Response>();
    const status =
      exception instanceof ResourceIdempotencyConflictError
        ? 409
        : exception instanceof HttpException
          ? exception.getStatus()
          : 500;
    const correlationId = this.correlationId(request);
    const problem = this.problem(exception, status);

    if (status >= 500) {
      this.logger.error({
        event: 'request_failed',
        status,
        code: problem.code,
        correlationId,
        errorName: exception instanceof Error ? exception.name : 'UnknownError',
      });
    }

    response
      .status(status)
      .type('application/problem+json')
      .setHeader('x-correlation-id', correlationId)
      .json({
        ...problem,
        correlationId,
      });
  }

  private correlationId(request: Request): string {
    const supplied = request.header('x-correlation-id');
    return supplied && supplied.length <= 128 ? supplied : randomUUID();
  }

  private problem(exception: unknown, status: number): SafeProblem {
    if (exception instanceof ResourceIdempotencyConflictError) {
      return this.canonical(status, 'IDEMPOTENCY_CONFLICT', 'Idempotency key conflict');
    }

    if (exception instanceof HttpException) {
      const response = exception.getResponse();
      if (this.isSafeProblem(response, status)) return response;
    }

    const code = this.code(status);
    return this.canonical(status, code, this.title(status));
  }

  private canonical(status: number, code: string, title: string): SafeProblem {
    return { type: `https://mentalbridge.io/errors/${code}`, title, status, code };
  }

  private isSafeProblem(value: unknown, status: number): value is SafeProblem {
    if (typeof value !== 'object' || value === null) return false;
    const problem = value as Record<string, unknown>;
    if (
      problem.status !== status ||
      typeof problem.type !== 'string' ||
      !problem.type.startsWith('https://mentalbridge.io/errors/') ||
      typeof problem.title !== 'string' ||
      problem.title.length > 200 ||
      typeof problem.code !== 'string' ||
      !/^[A-Z0-9_]{1,64}$/.test(problem.code)
    ) {
      return false;
    }
    if (problem.fieldViolations === undefined) return true;
    return (
      Array.isArray(problem.fieldViolations) &&
      problem.fieldViolations.length <= 32 &&
      problem.fieldViolations.every((violation) => {
        if (typeof violation !== 'object' || violation === null) return false;
        const item = violation as Record<string, unknown>;
        return (
          typeof item.field === 'string' &&
          /^[A-Za-z0-9_.-]{1,128}$/.test(item.field) &&
          typeof item.message === 'string' &&
          item.message.length <= 300
        );
      })
    );
  }

  private code(status: number): string {
    if (status === 400) return 'REQUEST_REJECTED';
    if (status === 401) return 'UNAUTHORIZED';
    if (status === 403) return 'FORBIDDEN';
    if (status === 404) return 'RESOURCE_NOT_FOUND';
    if (status === 409) return 'REQUEST_CONFLICT';
    if (status === 422) return 'VALIDATION_ERROR';
    if (status === 503) return 'DEPENDENCY_UNAVAILABLE';
    return status >= 500 ? 'INTERNAL_ERROR' : 'REQUEST_REJECTED';
  }

  private title(status: number): string {
    if (status === 400) return 'Request rejected';
    if (status === 401) return 'Authentication required';
    if (status === 403) return 'Access forbidden';
    if (status === 404) return 'Resource not found';
    if (status === 409) return 'Request conflict';
    if (status === 422) return 'Validation failed';
    if (status === 503) return 'Dependency unavailable';
    return status >= 500 ? 'An unexpected error occurred' : 'Request rejected';
  }
}
