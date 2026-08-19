import { Request, Response, NextFunction } from 'express';
import { DomainError } from './errors.js';
import logger from './logger.js';

export function errorHandler(err: Error, req: Request, res: Response, _next: NextFunction): void {
  const correlationId = (req.headers['x-correlation-id'] as string | undefined) ?? null;

  if (err instanceof DomainError) {
    res.status(err.status).json({
      type: `https://mentalbridge.io/errors/${err.code}`,
      title: err.message,
      status: err.status,
      code: err.code,
      correlationId,
    });
    return;
  }

  logger.error({ event: 'unhandled_error', error: err.message, correlationId });

  res.status(500).json({
    type: 'https://mentalbridge.io/errors/INTERNAL_ERROR',
    title: 'An unexpected error occurred.',
    status: 500,
    code: 'INTERNAL_ERROR',
    correlationId,
  });
}
