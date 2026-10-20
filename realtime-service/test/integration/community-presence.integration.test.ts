import { randomUUID } from 'node:crypto';
import { GenericContainer, type StartedTestContainer, Wait } from 'testcontainers';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { CommunityPresence } from '../../src/community/community-presence.js';
import { RedisService } from '../../src/database/redis.service.js';
import { testConfiguration } from '../fixtures/test-configuration.js';

describe('Community atomic foreground leases on real Redis', { concurrent: false }, () => {
  let container: StartedTestContainer;
  let redis: RedisService;
  let presence: CommunityPresence;
  let stopped = false;
  const subject = randomUUID();
  const expiry = () => Math.floor(Date.now() / 1000) + 300;
  beforeAll(async () => {
    container = await new GenericContainer('redis:7.4-alpine')
      .withExposedPorts(6379)
      .withWaitStrategy(Wait.forLogMessage(/Ready to accept connections/))
      .start();
    redis = new RedisService({
      ...testConfiguration('synthetic-key'),
      REDIS_URL: 'redis://' + container.getHost() + ':' + String(container.getMappedPort(6379)),
    });
    presence = new CommunityPresence(redis);
  });
  afterEach(async () => {
    if (!stopped) await redis.execute((client) => client.flushDb());
  });
  afterAll(async () => {
    await redis.onApplicationShutdown();
    if (!stopped) await container.stop();
  });

  it('aggregates two devices and removes only the background or disconnected lease', async () => {
    expect((await presence.states([subject]))[0]?.state).toBe('OFFLINE');
    expect(await presence.heartbeat(subject, 'device-a', true, expiry())).toBe(true);
    expect(await presence.heartbeat(subject, 'device-b', true, expiry())).toBe(false);
    const online = (await presence.states([subject]))[0];
    expect(online?.state).toBe('ONLINE');
    expect(online?.expiresIn).toBeGreaterThan(59000);
    expect(await presence.heartbeat(subject, 'device-a', false, expiry())).toBe(false);
    expect((await presence.states([subject]))[0]?.state).toBe('ONLINE');
    expect(await presence.remove(subject, 'device-b')).toBe(true);
    expect((await presence.states([subject]))[0]?.state).toBe('OFFLINE');
  });

  it('uses server expiry and prunes crash/stale leases without cached ONLINE', async () => {
    await presence.heartbeat(subject, 'crashed-device', true, expiry());
    await redis.execute((client) =>
      client.zAdd('community:v1:presence:' + subject, {
        score: Date.now() + 100,
        value: 'crashed-device',
      }),
    );
    await vi.waitFor(async () => {
      expect((await presence.states([subject]))[0]?.state).toBe('OFFLINE');
    });
    await redis.execute((client) =>
      client.zAdd('community:v1:presence:' + subject, {
        score: Date.now() - 1,
        value: 'stale-device',
      }),
    );
    expect((await presence.states([subject]))[0]?.state).toBe('OFFLINE');
  });

  it('limits foreground renewal but accepts immediate background clearing', async () => {
    await presence.heartbeat(subject, 'device', true, expiry());
    await expect(presence.heartbeat(subject, 'device', true, expiry())).rejects.toMatchObject({
      code: 'RATE_LIMITED',
    });
    await presence.heartbeat(subject, 'device', false, expiry());
    expect((await presence.states([subject]))[0]?.state).toBe('OFFLINE');
    await presence.remove(subject, 'device');
    await presence.heartbeat(subject, 'new-socket', true, expiry());
    expect((await presence.states([subject]))[0]?.state).toBe('ONLINE');
  });

  it('atomically preserves the remaining lease during simultaneous device changes', async () => {
    await Promise.all(
      ['a', 'b', 'c', 'd', 'e'].map((socket) =>
        presence.heartbeat(subject, socket, true, expiry()),
      ),
    );
    await Promise.all(['a', 'b', 'c', 'd'].map((socket) => presence.remove(subject, socket)));
    expect((await presence.states([subject]))[0]?.state).toBe('ONLINE');
    await presence.remove(subject, 'e');
    expect((await presence.states([subject]))[0]?.state).toBe('OFFLINE');
  });

  it('bounds a lease by original Identity expiry and rejects an expired identity', async () => {
    await expect(presence.heartbeat(subject, 'expired', true, 1)).rejects.toMatchObject({
      code: 'UNAUTHENTICATED',
    });
    await presence.heartbeat(subject, 'short-session', true, Math.floor(Date.now() / 1000) + 3);
    expect((await presence.states([subject]))[0]?.expiresIn).toBeLessThanOrEqual(3000);
  });

  it('returns UNKNOWN and rejects renewals after a real Redis failure', async () => {
    await presence.heartbeat(subject, 'device', true, expiry());
    await container.stop();
    stopped = true;
    expect((await presence.states([subject]))[0]?.state).toBe('UNKNOWN');
    await expect(presence.heartbeat(subject, 'device', true, expiry())).rejects.toMatchObject({
      code: 'DEPENDENCY_UNAVAILABLE',
    });
  });
});
