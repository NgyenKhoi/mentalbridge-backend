import {
  Module,
  type DynamicModule,
  type MiddlewareConsumer,
  type NestModule,
  type Provider,
} from '@nestjs/common';

import type { ConversationEligibility } from './conversations/conversation-eligibility.js';
import { UnavailableConversationEligibility } from './conversations/conversation-eligibility.js';
import type { ServiceConfiguration } from './configuration/configuration.js';
import { MongoDatabaseService } from './database/mongo-database.service.js';
import { RedisService } from './database/redis.service.js';
import { HealthController } from './health/health.controller.js';
import { HistoryController } from './history/history.controller.js';
import { MessageEncryptionService } from './messages/message-encryption.service.js';
import { MessageRepository } from './messages/message.repository.js';
import { MessageService } from './messages/message.service.js';
import { createLogger } from './observability/logger.js';
import { createMetrics } from './observability/metrics.js';
import { MetricsController } from './observability/metrics.controller.js';
import { RequestLoggingMiddleware } from './observability/request-logging.middleware.js';
import { PresenceService } from './presence/presence.service.js';
import { HttpJwtGuard } from './security/http-jwt.guard.js';
import { IdentityJwtVerifier } from './security/identity-jwt-verifier.js';
import {
  CONFIGURATION_TOKEN,
  ELIGIBILITY_TOKEN,
  LOGGER_TOKEN,
  METRICS_TOKEN,
} from './shared/tokens.js';
import { RealtimeGateway } from './websocket/realtime.gateway.js';

export interface ApplicationDependencies {
  readonly mongo?: MongoDatabaseService;
  readonly redis?: RedisService;
  readonly eligibility?: ConversationEligibility;
}

@Module({})
export class AppModule implements NestModule {
  static register(
    configuration: ServiceConfiguration,
    dependencies: ApplicationDependencies = {},
  ): DynamicModule {
    const mongoProvider: Provider = dependencies.mongo
      ? { provide: MongoDatabaseService, useValue: dependencies.mongo }
      : MongoDatabaseService;
    const redisProvider: Provider = dependencies.redis
      ? { provide: RedisService, useValue: dependencies.redis }
      : RedisService;
    const eligibilityProvider: Provider = dependencies.eligibility
      ? { provide: ELIGIBILITY_TOKEN, useValue: dependencies.eligibility }
      : { provide: ELIGIBILITY_TOKEN, useClass: UnavailableConversationEligibility };
    return {
      module: AppModule,
      controllers: [HealthController, MetricsController, HistoryController],
      providers: [
        { provide: CONFIGURATION_TOKEN, useValue: configuration },
        { provide: LOGGER_TOKEN, useValue: createLogger(configuration) },
        { provide: METRICS_TOKEN, useValue: createMetrics(configuration) },
        mongoProvider,
        redisProvider,
        eligibilityProvider,
        IdentityJwtVerifier,
        HttpJwtGuard,
        PresenceService,
        MessageEncryptionService,
        MessageRepository,
        MessageService,
        RealtimeGateway,
        RequestLoggingMiddleware,
      ],
    };
  }

  configure(consumer: MiddlewareConsumer): void {
    consumer.apply(RequestLoggingMiddleware).forRoutes('*');
  }
}
