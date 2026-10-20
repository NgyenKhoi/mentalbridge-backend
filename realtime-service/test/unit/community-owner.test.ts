import { randomUUID } from 'node:crypto';
import { exportPKCS8, exportSPKI, generateKeyPair } from 'jose';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { CommunityOwnerClient } from '../../src/community/community-owner.client.js';
import { communityConfiguration } from '../../src/community/community-configuration.js';
import { commandSchema, factSchema } from '../../src/community/community-schema.js';
import {
  CommunityOwnerFixture,
  communityBob,
  communityConversation,
} from '../fixtures/community-owner.js';
import { testConfiguration } from '../fixtures/test-configuration.js';

describe('Community current-owner client', () => {
  let fixture: CommunityOwnerFixture;
  let client: CommunityOwnerClient;
  beforeAll(async () => {
    const keys = await generateKeyPair('RS256', { extractable: true });
    fixture = new CommunityOwnerFixture(keys.publicKey);
    await fixture.start();
    const configuration = testConfiguration(await exportSPKI(keys.publicKey));
    client = new CommunityOwnerClient({
      ...configuration,
      COMMUNITY: {
        ...configuration.COMMUNITY,
        baseUrl: fixture.url,
        privateKey: await exportPKCS8(keys.privateKey),
        keyId: 'realtime-test',
      },
    });
    await client.onModuleInit();
  });
  afterAll(async () => {
    await client.onApplicationShutdown();
    await fixture.close();
  });
  beforeEach(() => {
    fixture.reset();
  });
  it('signs fresh target-bound owner reads and validates exact response bindings', async () => {
    const message = fixture.save();
    await client.subscribe(communityBob, communityConversation, randomUUID());
    const baseline = await client.replay(communityBob, communityConversation, null, randomUUID());
    expect(baseline.messageIds).toEqual([]);
    expect(
      await client.message(communityBob, communityConversation, message.messageId, randomUUID()),
    ).toEqual(message);
    expect(fixture.calls.at(-1)).toMatchObject({
      subject: communityBob,
      scope: 'community:message:project',
      conversation: communityConversation,
      message: message.messageId,
    });
    fixture.malformed = true;
    await expect(
      client.message(communityBob, communityConversation, message.messageId, randomUUID()),
    ).rejects.toMatchObject({ code: 'DEPENDENCY_UNAVAILABLE' });
  });
  it('fails closed after revocation or owner failure without retaining a positive grant', async () => {
    const message = fixture.save();
    await client.message(communityBob, communityConversation, message.messageId, randomUUID());
    fixture.denied.add(communityBob);
    await expect(
      client.message(communityBob, communityConversation, message.messageId, randomUUID()),
    ).rejects.toMatchObject({ code: 'TARGET_UNAVAILABLE' });
    fixture.denied.clear();
    fixture.unavailable = true;
    await expect(
      client.subscribe(communityBob, communityConversation, randomUUID()),
    ).rejects.toMatchObject({ code: 'DEPENDENCY_UNAVAILABLE' });
  });
  it('requires enabled feature credentials and closes actor injection and poison facts', () => {
    expect(() => communityConfiguration({ REALTIME_COMMUNITY_ENABLED: 'true' })).toThrow();
    const command = {
      schemaVersion: 1,
      commandId: randomUUID(),
      correlationId: randomUUID(),
      commandType: 'community.conversation.subscribe',
      payload: { conversationId: communityConversation },
    };
    expect(commandSchema.safeParse(command).success).toBe(true);
    expect(
      commandSchema.safeParse({
        ...command,
        payload: { ...command.payload, actorId: communityBob },
      }).success,
    ).toBe(false);
    const fact = fixture.fact(fixture.save());
    expect(factSchema.safeParse(fact).success).toBe(true);
    expect(
      factSchema.safeParse({ ...fact, payload: { ...fact.payload, content: 'private' } }).success,
    ).toBe(false);
    expect(factSchema.safeParse({ ...fact, aggregateId: randomUUID() }).success).toBe(false);
  });
  it('bounds a slow owner read to the two-second deadline', async () => {
    let release: () => void = () => undefined;
    fixture.requestGate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const started = Date.now();
    try {
      await expect(
        client.subscribe(communityBob, communityConversation, randomUUID()),
      ).rejects.toMatchObject({ code: 'DEPENDENCY_UNAVAILABLE' });
      expect(Date.now() - started).toBeLessThan(5000);
    } finally {
      release();
      delete fixture.requestGate;
    }
  });
  it('opens a bounded failure circuit without issuing a stale positive decision', async () => {
    await client.session(communityBob);
    fixture.unavailable = true;
    for (let attempt = 0; attempt < 5; attempt++)
      await expect(client.session(communityBob)).rejects.toMatchObject({
        code: 'DEPENDENCY_UNAVAILABLE',
      });
    const calls = fixture.calls.length;
    await expect(client.session(communityBob)).rejects.toMatchObject({
      code: 'DEPENDENCY_UNAVAILABLE',
    });
    expect(fixture.calls).toHaveLength(calls);
  });
});
