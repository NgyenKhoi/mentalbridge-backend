import 'reflect-metadata';

import type { INestApplication } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import helmet from 'helmet';

import { AppModule, type ApplicationDependencies } from './app.module.js';
import { loadConfiguration, type ServiceConfiguration } from './configuration/configuration.js';
import { ProblemDetailsFilter } from './http/problem-details.filter.js';
import { ConfiguredSocketIoAdapter } from './websocket/configured-socket-io.adapter.js';

export const createApplication = async (
  configuration: ServiceConfiguration = loadConfiguration(),
  dependencies: ApplicationDependencies = {},
): Promise<INestApplication> => {
  const app = await NestFactory.create(AppModule.register(configuration, dependencies), {
    logger: false,
  });
  app.use(helmet());
  app.enableCors({
    origin: configuration.ALLOWED_ORIGINS.length ? configuration.ALLOWED_ORIGINS : false,
    credentials: true,
  });
  app.useWebSocketAdapter(new ConfiguredSocketIoAdapter(app, configuration));
  app.useGlobalFilters(new ProblemDetailsFilter());
  app.enableShutdownHooks();
  return app;
};
