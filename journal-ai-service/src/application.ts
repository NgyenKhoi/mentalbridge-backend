import "reflect-metadata";

import type { INestApplication } from "@nestjs/common";
import { NestFactory } from "@nestjs/core";

import { AppModule, type ApplicationDependencies } from "./app.module.js";
import {
  loadConfiguration,
  type ServiceConfiguration,
} from "./configuration/configuration.js";
import { VoiceGlobalFilter } from "./companion-voice/voice-session-boundary.js";

export const createApplication = async (
  configuration: ServiceConfiguration = loadConfiguration(),
  dependencies: ApplicationDependencies = {},
): Promise<INestApplication> => {
  const app = await NestFactory.create(
    AppModule.register(configuration, dependencies),
    { logger: false },
  );
  app.useGlobalFilters(new VoiceGlobalFilter());
  return app;
};
