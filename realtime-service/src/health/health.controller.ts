import { Controller, Get, Inject, Optional, ServiceUnavailableException } from '@nestjs/common';
import { CommunityFanout } from '../community/community-fanout.js';

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
    @Optional() private readonly community?: CommunityFanout,
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
    const communityReady = this.community?.available() ?? true;
    this.metrics.dependencyChecks.inc({
      dependency: 'redis',
      result: redisReady ? 'connected' : 'degraded',
    });
    return {
      status: redisReady && communityReady ? ('ok' as const) : ('degraded' as const),
      service: 'realtime-service' as const,
      mongodb: 'connected' as const,
      redis: redisReady ? ('connected' as const) : ('degraded' as const),
      ...(this.community
        ? { community: communityReady ? ('connected' as const) : ('degraded' as const) }
        : {}),
    };
  }
}
