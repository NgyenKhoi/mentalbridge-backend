import { randomUUID } from 'node:crypto';
import { createServer, type Server } from 'node:http';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { ContentNotificationClient } from '../../src/notifications/content-notification.client.js';
import { specialistLiveConfiguration } from '../../src/notifications/specialist-live.configuration.js';
import { createMetrics } from '../../src/observability/metrics.js';
import { testConfiguration } from '../fixtures/test-configuration.js';

describe('Content notification current eligibility adapter', () => {
  let server: Server;
  let url: string;
  let client: ContentNotificationClient;
  let mode = 'eligible';
  let status = 200;
  let requests: {
    authorization: string | undefined;
    correlationId: string | string[] | undefined;
    url: string | undefined;
  }[] = [];
  const notificationId = randomUUID();
  const correlationId = randomUUID();
  beforeAll(async () => {
    server = createServer((request, response) => {
      requests.push({
        authorization: request.headers.authorization,
        correlationId: request.headers['x-correlation-id'],
        url: request.url,
      });
      if (mode === 'timeout') return;
      response.writeHead(status, {
        'content-type': 'application/json',
        ...(mode === 'redirect' ? { location: '/redirected' } : {}),
      });
      response.end(
        mode === 'malformed'
          ? '{bad'
          : mode === 'oversized'
            ? 'a'.repeat(2048)
            : JSON.stringify(
                mode === 'extra'
                  ? { notificationId, body: 'private' }
                  : { notificationId: mode === 'wrong-id' ? randomUUID() : notificationId },
              ),
      );
    });
    await new Promise<void>((resolve) => {
      server.listen(0, '127.0.0.1', resolve);
    });
    const address = server.address();
    if (!address || typeof address === 'string') throw new Error('Owner fixture unavailable');
    url = `http://127.0.0.1:${String(address.port)}`;
  });
  beforeEach(() => {
    mode = 'eligible';
    status = 200;
    requests = [];
    const configuration = testConfiguration('synthetic', {
      SPECIALIST_LIVE: { enabled: true, baseUrl: url, timeoutMs: 2000, brokers: ['broker:9092'] },
    });
    client = new ContentNotificationClient(configuration, createMetrics(configuration));
  });
  afterEach(async () => {
    await client.onApplicationShutdown();
  });
  afterAll(async () => {
    server.closeAllConnections();
    await new Promise<void>((resolve, reject) => {
      server.close((error) => {
        if (error) reject(error);
        else resolve();
      });
    });
  });

  it('forwards the existing bearer to the exact owner URL and never caches a positive grant', async () => {
    expect(await client.check('synthetic-bearer', notificationId, correlationId, 1000)).toBe(
      'eligible',
    );
    expect(requests).toEqual([
      {
        authorization: 'Bearer synthetic-bearer',
        correlationId,
        url: `/internal/v1/notifications/${notificationId}/live-eligibility`,
      },
    ]);
    status = 404;
    expect(await client.check('synthetic-bearer', notificationId, correlationId, 1000)).toBe(
      'ineligible',
    );
    expect(requests).toHaveLength(2);
  });

  it.each([401, 403, 404, 422])('fails closed on owner %i', async (responseStatus) => {
    status = responseStatus;
    expect(await client.check('synthetic-bearer', notificationId, correlationId, 1000)).toBe(
      'ineligible',
    );
  });

  it.each(['malformed', 'oversized', 'wrong-id', 'extra', 'timeout', 'redirect'])(
    'fails closed on %s owner responses',
    async (responseMode) => {
      mode = responseMode;
      if (mode === 'redirect') status = 302;
      const started = Date.now();
      expect(
        await client.check(
          'synthetic-bearer',
          notificationId,
          correlationId,
          mode === 'timeout' ? 100 : 1000,
        ),
      ).toBe('unavailable');
      expect(Date.now() - started).toBeLessThan(1000);
      expect(requests).toHaveLength(1);
    },
  );

  it('uses the remaining socket/event lifetime as the tighter request deadline', async () => {
    mode = 'timeout';
    const started = Date.now();
    expect(await client.check('synthetic-bearer', notificationId, correlationId, 20)).toBe(
      'unavailable',
    );
    expect(Date.now() - started).toBeLessThan(300);
  });

  it('opens a bounded failure circuit without returning a stale authorization', async () => {
    status = 503;
    for (let index = 0; index < 5; index++)
      expect(await client.check('synthetic-bearer', notificationId, correlationId, 1000)).toBe(
        'unavailable',
      );
    status = 200;
    expect(await client.check('synthetic-bearer', notificationId, correlationId, 1000)).toBe(
      'unavailable',
    );
    expect(requests).toHaveLength(5);
  });

  it('validates independent opt-in configuration and safe URL/deadline boundaries', () => {
    expect(specialistLiveConfiguration()).toEqual({
      enabled: false,
      baseUrl: '',
      timeoutMs: 2000,
      brokers: [],
    });
    expect(() =>
      specialistLiveConfiguration({ REALTIME_SPECIALIST_LIVE_ENABLED: 'true' }),
    ).toThrow();
    const configured = {
      REALTIME_SPECIALIST_LIVE_ENABLED: 'true',
      CONTENT_NOTIFICATION_SERVICE_URL: url,
      KAFKA_BOOTSTRAP_SERVERS: 'broker:9092',
    };
    expect(specialistLiveConfiguration(configured).enabled).toBe(true);
    for (const value of [
      'ftp://owner.test',
      'http://user:pass@owner.test',
      'https://owner.test?token=x',
      'https://owner.test/path',
    ])
      expect(() =>
        specialistLiveConfiguration({ ...configured, CONTENT_NOTIFICATION_SERVICE_URL: value }),
      ).toThrow();
    expect(() =>
      specialistLiveConfiguration({ ...configured, CONTENT_NOTIFICATION_TIMEOUT_MS: '5001' }),
    ).toThrow();
    expect(() => specialistLiveConfiguration({ ...configured, NODE_ENV: 'production' })).toThrow();
  });
});
