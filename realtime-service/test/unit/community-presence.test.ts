import { randomUUID } from 'node:crypto';
import { exportPKCS8, exportSPKI, generateKeyPair } from 'jose';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { CommunityOwnerClient } from '../../src/community/community-owner.client.js';
import { CommunityPresence } from '../../src/community/community-presence.js';
import { CommunityPresenceProjection } from '../../src/community/community-presence-projection.js';
import { commandSchema } from '../../src/community/community-schema.js';
import { RedisService } from '../../src/database/redis.service.js';
import {
  CommunityOwnerFixture,
  communityAlice,
  communityBob,
  communityConversation,
} from '../fixtures/community-owner.js';
import { testConfiguration } from '../fixtures/test-configuration.js';

describe('Community privacy-aware presence projections', () => {
  let fixture: CommunityOwnerFixture;
  let owner: CommunityOwnerClient;
  let redis: RedisService;
  let store: CommunityPresence;
  let projection: CommunityPresenceProjection;
  beforeAll(async () => {
    const keys = await generateKeyPair('RS256', { extractable: true });
    fixture = new CommunityOwnerFixture(keys.publicKey);
    await fixture.start();
    const configuration = testConfiguration(await exportSPKI(keys.publicKey));
    owner = new CommunityOwnerClient({
      ...configuration,
      COMMUNITY: {
        ...configuration.COMMUNITY,
        baseUrl: fixture.url,
        privateKey: await exportPKCS8(keys.privateKey),
        keyId: 'realtime-test',
      },
    });
    await owner.onModuleInit();
    redis = new RedisService(configuration);
    store = new CommunityPresence(redis);
    projection = new CommunityPresenceProjection(owner, store);
  });
  beforeEach(() => {
    vi.restoreAllMocks();
    fixture.reset();
  });
  afterAll(async () => {
    await owner.onApplicationShutdown();
    await redis.onApplicationShutdown();
    await fixture.close();
  });

  it('validates foreground input without accepting actor, duration or online claims', () => {
    const input = {
      schemaVersion: 1,
      commandId: randomUUID(),
      correlationId: randomUUID(),
      commandType: 'community.presence.heartbeat',
      payload: { foreground: true },
    };
    expect(commandSchema.safeParse(input).success).toBe(true);
    for (const payload of [
      { foreground: 'true' },
      { foreground: true, actorId: communityAlice },
      { foreground: true, ttl: 60000 },
      { online: true },
    ])
      expect(commandSchema.safeParse({ ...input, payload }).success).toBe(false);
  });

  it('never looks up hidden subjects or publishes UNKNOWN solely to reveal opt-out', async () => {
    const states = vi.spyOn(store, 'states').mockResolvedValue([]);
    expect(
      (await projection.snapshot(communityBob, communityConversation, randomUUID())).projections,
    ).toEqual([]);
    expect(states).toHaveBeenCalledWith([]);
    fixture.presenceOptIn.add(communityAlice);
    fixture.presenceInvisible.add(communityAlice);
    expect(
      (await projection.snapshot(communityBob, communityConversation, randomUUID())).projections,
    ).toEqual([]);
    expect(states).toHaveBeenLastCalledWith([]);
  });

  it('reauthorizes after Redis and discards visibility revoked during the read', async () => {
    fixture.presenceOptIn.add(communityAlice);
    vi.spyOn(store, 'states').mockImplementation(() => {
      fixture.presenceHidden.add(communityBob + ':' + communityAlice);
      return Promise.resolve([{ subject: communityAlice, state: 'ONLINE', expiresIn: 60000 }]);
    });
    expect(
      (await projection.snapshot(communityBob, communityConversation, randomUUID())).projections,
    ).toEqual([]);
    expect(
      fixture.calls.filter((call) => call.scope === 'community:presence:project'),
    ).toHaveLength(2);
  });

  it('returns UNKNOWN only for currently opted-in authorized peers on Redis uncertainty', async () => {
    fixture.presenceOptIn.add(communityAlice);
    vi.spyOn(store, 'states').mockResolvedValue([
      { subject: communityAlice, state: 'UNKNOWN', expiresIn: 0 },
    ]);
    const snapshot = await projection.snapshot(communityBob, communityConversation, randomUUID());
    expect(snapshot.projections).toEqual([
      expect.objectContaining({ communityProfileId: communityAlice, state: 'UNKNOWN' }),
    ]);
    fixture.denied.add(communityBob);
    await expect(
      projection.snapshot(communityBob, communityConversation, randomUUID()),
    ).rejects.toMatchObject({ code: 'TARGET_UNAVAILABLE' });
  });

  it('rejects malformed private peer responses without a stale permission fallback', async () => {
    fixture.presenceOptIn.add(communityAlice);
    expect(await owner.presence(communityBob, communityConversation, randomUUID())).toHaveLength(1);
    fixture.malformed = true;
    await expect(
      owner.presence(communityBob, communityConversation, randomUUID()),
    ).rejects.toMatchObject({ code: 'DEPENDENCY_UNAVAILABLE' });
  });
});
