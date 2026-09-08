import { Global, Module, type DynamicModule, type Provider } from '@nestjs/common';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import { MongoDatabaseService } from '../database/mongo-database.service.js';
import { RedisService } from '../database/redis.service.js';
import { createLogger } from '../observability/logger.js';
import { createMetrics } from '../observability/metrics.js';
import { CONFIGURATION_TOKEN, LOGGER_TOKEN, METRICS_TOKEN } from '../shared/tokens.js';

export interface PlatformDependencies {
  readonly mongo?: MongoDatabaseService;
  readonly redis?: RedisService;
}

@Global()
@Module({})
export class PlatformModule {
  static register(
    configuration: ServiceConfiguration,
    dependencies: PlatformDependencies = {},
  ): DynamicModule {
    const mongoProvider: Provider = dependencies.mongo
      ? { provide: MongoDatabaseService, useValue: dependencies.mongo }
      : MongoDatabaseService;
    const redisProvider: Provider = dependencies.redis
      ? { provide: RedisService, useValue: dependencies.redis }
      : RedisService;
    return {
      module: PlatformModule,
      global: true,
      providers: [
        { provide: CONFIGURATION_TOKEN, useValue: configuration },
        { provide: LOGGER_TOKEN, useValue: createLogger(configuration) },
        { provide: METRICS_TOKEN, useValue: createMetrics(configuration) },
        mongoProvider,
        redisProvider,
      ],
      exports: [
        CONFIGURATION_TOKEN,
        LOGGER_TOKEN,
        METRICS_TOKEN,
        MongoDatabaseService,
        RedisService,
      ],
    };
  }
}
