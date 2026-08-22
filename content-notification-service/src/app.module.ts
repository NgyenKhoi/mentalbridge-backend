import { Module, type DynamicModule, type Provider } from '@nestjs/common';

import { CONFIGURATION_TOKEN, READINESS_PROBE_TOKEN } from './application.tokens.js';
import type { ServiceConfiguration } from './configuration/configuration.js';
import { DatabaseService, type ReadinessProbe } from './database/database.service.js';
import { HealthController } from './health/health.controller.js';

export interface ApplicationDependencies {
  readonly readinessProbe?: ReadinessProbe;
}

@Module({})
// eslint-disable-next-line @typescript-eslint/no-extraneous-class
class ContentNotificationModule {}

export const createAppModule = (
  configuration: ServiceConfiguration,
  dependencies: ApplicationDependencies = {},
): DynamicModule => {
  const readinessProvider: Provider = dependencies.readinessProbe
    ? { provide: READINESS_PROBE_TOKEN, useValue: dependencies.readinessProbe }
    : { provide: READINESS_PROBE_TOKEN, useExisting: DatabaseService };

  return {
    module: ContentNotificationModule,
    controllers: [HealthController],
    providers: [
      { provide: CONFIGURATION_TOKEN, useValue: configuration },
      DatabaseService,
      readinessProvider,
    ],
  };
};
