import { Module, type DynamicModule } from '@nestjs/common';
import { CommunityModule } from '../community/community.module.js';

import { HealthController } from '../health/health.controller.js';
import { MetricsController } from '../observability/metrics.controller.js';
import { RequestLoggingMiddleware } from '../observability/request-logging.middleware.js';

@Module({
  controllers: [HealthController, MetricsController],
  providers: [RequestLoggingMiddleware],
  exports: [RequestLoggingMiddleware],
})
export class OperationsModule {
  static register(communityEnabled: boolean): DynamicModule {
    return { module: OperationsModule, imports: communityEnabled ? [CommunityModule] : [] };
  }
}
