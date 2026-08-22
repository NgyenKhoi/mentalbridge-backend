import "reflect-metadata";

import type { INestApplication } from "@nestjs/common";
import { NestFactory } from "@nestjs/core";

import {
  AppModule,
  type ApplicationDependencies,
} from "./app.module.js";
import {
  loadConfiguration,
  type ServiceConfiguration,
} from "./configuration/configuration.js";
import { ProblemDetailsFilter } from "./http/problem-details.filter.js";

export const createApplication = async (
  configuration: ServiceConfiguration = loadConfiguration(),
  dependencies: ApplicationDependencies = {},
): Promise<INestApplication> => {
  const app = await NestFactory.create(
    AppModule.register(configuration, dependencies),
    { logger: false },
  );
  app.useGlobalFilters(new ProblemDetailsFilter());
  return app;
};
