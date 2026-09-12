import { Module, type DynamicModule, type Provider } from '@nestjs/common';

import {
  CONFIGURATION_TOKEN,
  READINESS_PROBE_TOKEN,
  DATABASE_SERVICE_TOKEN,
  RESOURCE_REPOSITORY_TOKEN,
  RESOURCE_SERVICE_TOKEN,
  E2E_OUTAGE_STATE_TOKEN,
} from './application.tokens.js';
import type { ServiceConfiguration } from './configuration/configuration.js';
import { DatabaseService, type ReadinessProbe } from './database/database.service.js';
import { HealthController } from './health/health.controller.js';
import { ResourceController } from './resources/resource.controller.js';
import { ResourceRepository } from './resources/resource.repository.js';
import { ResourceService } from './resources/resource.service.js';
import { E2eOutageController } from './e2e/e2e-outage.controller.js';

export interface ApplicationDependencies {
  readonly readinessProbe?: ReadinessProbe;
  readonly resourceRepository?: ResourceRepository;
  readonly outageState?: { enabled: boolean };
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

  const dbServiceProvider: Provider = {
    provide: DATABASE_SERVICE_TOKEN,
    useExisting: DatabaseService,
  };

  const repositoryProvider: Provider = dependencies.resourceRepository
    ? { provide: RESOURCE_REPOSITORY_TOKEN, useValue: dependencies.resourceRepository }
    : { provide: RESOURCE_REPOSITORY_TOKEN, useClass: ResourceRepository };

  const serviceProvider: Provider = {
    provide: RESOURCE_SERVICE_TOKEN,
    useClass: ResourceService,
  };

  return {
    module: ContentNotificationModule,
    controllers: [HealthController, ResourceController, E2eOutageController],
    providers: [
      { provide: CONFIGURATION_TOKEN, useValue: configuration },
      {
        provide: E2E_OUTAGE_STATE_TOKEN,
        useValue: dependencies.outageState ?? { enabled: false },
      },
      DatabaseService,
      readinessProvider,
      dbServiceProvider,
      repositoryProvider,
      serviceProvider,
    ],
  };
};
