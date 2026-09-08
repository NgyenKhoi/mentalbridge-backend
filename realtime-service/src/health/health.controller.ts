import { Controller, Get, Inject, ServiceUnavailableException } from '@nestjs/common';

import { MongoDatabaseService } from '../database/mongo-database.service.js';
import { RedisService } from '../database/redis.service.js';
import type { RealtimeMetrics } from '../observability/metrics.js';
import { METRICS_TOKEN } from '../shared/tokens.js';

@Controller('health')
export class HealthController {
  constructor(
    private readonly mongo: MongoDatabaseService,
    private readonly redis: RedisService,
    @Inject(METRICS_TOKEN) private readonly metrics: RealtimeMetrics,
  ) {}

  @Get('live')
  live() {
    return { status: 'ok' as const, service: 'realtime-service' as const };
  }

  @Get('ready')
  async ready() {
    try {
      await this.mongo.ping();
      this.metrics.dependencyChecks.inc({ dependency: 'mongodb', result: 'connected' });
    } catch {
      this.metrics.dependencyChecks.inc({ dependency: 'mongodb', result: 'unavailable' });
      throw new ServiceUnavailableException('MongoDB is unavailable');
    }
    const redisReady = await this.redis.ping();
    this.metrics.dependencyChecks.inc({
      dependency: 'redis',
      result: redisReady ? 'connected' : 'degraded',
    });
    return {
      status: redisReady ? ('ok' as const) : ('degraded' as const),
      service: 'realtime-service' as const,
      mongodb: 'connected' as const,
      redis: redisReady ? ('connected' as const) : ('degraded' as const),
    };
  }
}
