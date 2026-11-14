import { Kafka } from 'kafkajs';
import type { Logger } from 'pino';
import { afterEach, expect, it, vi } from 'vitest';
import { CommunityFanout } from '../../src/community/community-fanout.js';
import type { ServiceConfiguration } from '../../src/configuration/configuration.js';
import type { RedisService } from '../../src/database/redis.service.js';
import type { RealtimeMetrics } from '../../src/observability/metrics.js';

afterEach(() => vi.restoreAllMocks());

it('keeps an idle Community fetch below the broker request deadline', () => {
  const consumer = vi.spyOn(Kafka.prototype, 'consumer');
  new CommunityFanout(
    {} as RedisService,
    {
      REDIS_URL: 'redis://127.0.0.1:6379',
      COMMUNITY: { brokers: ['127.0.0.1:9092'], groupId: 'test', channel: 'test' },
    } as ServiceConfiguration,
    {} as RealtimeMetrics,
    { warn: vi.fn() } as unknown as Logger,
  );
  const maxWait = consumer.mock.calls[0]?.[0].maxWaitTimeInMs;
  expect(maxWait).toBeGreaterThan(0);
  expect(maxWait).toBeLessThan(2000);
});
