import { Module } from '@nestjs/common';

import { HealthController } from '../health/health.controller.js';
import { MetricsController } from '../observability/metrics.controller.js';
import { RequestLoggingMiddleware } from '../observability/request-logging.middleware.js';

@Module({
  controllers: [HealthController, MetricsController],
  providers: [RequestLoggingMiddleware],
  exports: [RequestLoggingMiddleware],
})
export class OperationsModule {}
