import {
  Module,
  type DynamicModule,
  type MiddlewareConsumer,
  type NestModule,
} from '@nestjs/common';

import type { ConversationEligibility } from './conversations/conversation-eligibility.js';
import type { ServiceConfiguration } from './configuration/configuration.js';
import { MongoDatabaseService } from './database/mongo-database.service.js';
import { RedisService } from './database/redis.service.js';
import { RequestLoggingMiddleware } from './observability/request-logging.middleware.js';
import { OperationsModule } from './operations/operations.module.js';
import { PlatformModule } from './platform/platform.module.js';
import { WebsocketModule } from './websocket/websocket.module.js';

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
    return {
      module: AppModule,
      imports: [
        PlatformModule.register(configuration, dependencies),
        OperationsModule,
        WebsocketModule.register(dependencies.eligibility),
      ],
    };
  }

  configure(consumer: MiddlewareConsumer): void {
    consumer.apply(RequestLoggingMiddleware).forRoutes('*');
  }
}
