import 'reflect-metadata';

import type { INestApplication } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import type { NextFunction, Request, Response } from 'express';
import { randomUUID } from 'node:crypto';
import { pinoHttp } from 'pino-http';

import { createAppModule, type ApplicationDependencies } from './app.module.js';
import { loadConfiguration } from './configuration/configuration.js';
import { ProblemDetailsFilter } from './http/problem-details.filter.js';
import { createLogger } from './observability/logger.js';

export const createApplication = async (
  configuration = loadConfiguration(),
  dependencies: ApplicationDependencies = {},
): Promise<INestApplication> => {
  const logger = createLogger(configuration);
  const app = await NestFactory.create(createAppModule(configuration, dependencies), {
    logger: false,
  });

  app.use((request: Request, response: Response, next: NextFunction) => {
    const supplied = request.header('x-correlation-id');
    const correlationId =
      supplied &&
      /^[\da-f]{8}-[\da-f]{4}-[1-5][\da-f]{3}-[89ab][\da-f]{3}-[\da-f]{12}$/i.test(supplied)
        ? supplied
        : randomUUID();
    request.headers['x-correlation-id'] = correlationId;
    response.setHeader('x-correlation-id', correlationId);
    next();
  });
  app.use(pinoHttp({ logger }));
  app.enableCors({
    origin: configuration.ALLOWED_ORIGINS.length ? configuration.ALLOWED_ORIGINS : false,
    credentials: true,
  });
  app.useGlobalFilters(new ProblemDetailsFilter(logger));
  return app;
};
