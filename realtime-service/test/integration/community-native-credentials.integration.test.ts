import 'reflect-metadata';
import { createHash } from 'node:crypto';
import type { Server } from 'node:http';
import { Module, type INestApplication } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { exportPKCS8, exportSPKI, generateKeyPair, SignJWT, type CryptoKey } from 'jose';
import request from 'supertest';
import { GenericContainer, type StartedTestContainer, Wait } from 'testcontainers';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { z } from 'zod';
import {
  CommunityCredentials,
  CommunityCredentialController,
} from '../../src/community/community-credentials.js';
import { CommunityNativeCredentialController } from '../../src/community/community-native-credential.controller.js';
import { CommunityOwnerClient } from '../../src/community/community-owner.client.js';
import { RedisService } from '../../src/database/redis.service.js';
import { ProblemDetailsFilter } from '../../src/http/problem-details.filter.js';
import { IdentityJwtVerifier } from '../../src/security/identity-jwt-verifier.js';
import { SocketCredentialService } from '../../src/security/socket-credential.service.js';
import type { ServiceConfiguration } from '../../src/configuration/configuration.js';
import { CommunityOwnerFixture, communityAlice } from '../fixtures/community-owner.js';
import { testConfiguration } from '../fixtures/test-configuration.js';
import { issueToken } from '../fixtures/token.js';

@Module({})
class NativeCredentialTestModule {
  readonly feature = 'native-community-credentials';
}

const ticketSchema = z
  .object({
    schemaVersion: z.literal(1),
    namespace: z.literal('/community'),
    credential: z.string().min(32).max(512),
    expiresAt: z.iso.datetime({ offset: true }),
  })
  .strict();

describe('Native Community HTTP exchange with signed owner reads and real Redis', () => {
  let container: StartedTestContainer;
  let redis: RedisService;
  let owner: CommunityOwnerFixture;
  let ownerClient: CommunityOwnerClient;
  let credentials: CommunityCredentials;
  let appointment: SocketCredentialService;
  let app: INestApplication;
  let configuration: ServiceConfiguration;
  let identityKey: CryptoKey;
  let bffKey: CryptoKey;
  let token: string;
  let stopped = false;
  const http = () => request(app.getHttpServer() as Server);
  const endpoint = '/api/v1/community/socket-credentials';
  beforeAll(async () => {
    const identity = await generateKeyPair('RS256', { extractable: true });
    const realtime = await generateKeyPair('RS256', { extractable: true });
    const bff = await generateKeyPair('RS256', { extractable: true });
    identityKey = identity.privateKey;
    bffKey = bff.privateKey;
    owner = new CommunityOwnerFixture(realtime.publicKey);
    await owner.start();
    container = await new GenericContainer('redis:7.4-alpine')
      .withExposedPorts(6379)
      .withWaitStrategy(Wait.forLogMessage(/Ready to accept connections/))
      .start();
    const base = testConfiguration(await exportSPKI(identity.publicKey));
    configuration = {
      ...base,
      REDIS_URL: `redis://${container.getHost()}:${String(container.getMappedPort(6379))}`,
      COMMUNITY: {
        ...base.COMMUNITY,
        enabled: true,
        baseUrl: owner.url,
        privateKey: await exportPKCS8(realtime.privateKey),
        keyId: 'realtime-test',
        bffPublicKey: await exportSPKI(bff.publicKey),
        bffKeyId: 'bff-test',
      },
    };
    redis = new RedisService(configuration);
    ownerClient = new CommunityOwnerClient(configuration);
    await ownerClient.onModuleInit();
    credentials = new CommunityCredentials(redis, ownerClient, configuration);
    const verifier = new IdentityJwtVerifier(configuration);
    appointment = new SocketCredentialService(redis, configuration);
    app = await NestFactory.create(
      {
        module: NativeCredentialTestModule,
        controllers: [CommunityNativeCredentialController, CommunityCredentialController],
        providers: [
          { provide: IdentityJwtVerifier, useValue: verifier },
          { provide: CommunityCredentials, useValue: credentials },
        ],
      },
      { logger: false },
    );
    app.useGlobalFilters(new ProblemDetailsFilter());
    await app.init();
    token = await issueToken(identityKey, configuration);
  });
  beforeEach(async () => {
    owner.reset();
    if (!stopped) await redis.execute((client) => client.flushDb());
  });
  afterAll(async () => {
    await app.close();
    await ownerClient.onApplicationShutdown();
    await redis.onApplicationShutdown();
    await owner.close();
    if (!stopped) await container.stop();
  });
  async function ticket(accessToken = token) {
    const response = await http()
      .post(endpoint)
      .set('Authorization', 'Bearer ' + accessToken)
      .send({})
      .expect('Cache-Control', 'no-store')
      .expect(200);
    return ticketSchema.parse(response.body as unknown);
  }
  it('selects only the verified USER and atomically consumes once without storing a bearer', async () => {
    const value = await ticket();
    const key =
      'community:v1:ticket:' + createHash('sha256').update(value.credential).digest('hex');
    const record = await redis.execute((client) => client.get(key));
    const ttl = await redis.execute((client) => client.ttl(key));
    expect(ttl).toBeGreaterThan(0);
    expect(ttl).toBeLessThanOrEqual(30);
    const stored = z
      .object({ subject: z.uuid(), identityExpiresAt: z.number().int() })
      .strict()
      .parse(JSON.parse(record ?? '{}') as unknown);
    expect(stored.subject).toBe(communityAlice);
    expect(stored.identityExpiresAt).toBeGreaterThan(Math.floor(Date.now() / 1000));
    expect(record).not.toContain(token);
    const outcomes = await Promise.allSettled([
      credentials.consume(value.credential),
      credentials.consume(value.credential),
    ]);
    expect(outcomes.filter((item) => item.status === 'fulfilled')).toHaveLength(1);
    expect(owner.calls.filter((call) => call.scope === 'community:session:authorize')).toHaveLength(
      2,
    );
    await expect(appointment.consume(value.credential)).rejects.toThrow();
    const principal = {
      accountId: communityAlice,
      role: 'USER' as const,
      tokenId: 'synthetic',
      expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 300,
    };
    const other = await appointment.issue(principal, token);
    await expect(credentials.consume(other.accessToken)).rejects.toThrow();
  });
  it('caps the response and Redis TTL at the original verified Identity expiry', async () => {
    const short = await issueToken(identityKey, configuration, communityAlice, {
      expiresInSeconds: 8,
    });
    const value = await ticket(short);
    expect(Date.parse(value.expiresAt)).toBeLessThanOrEqual(Date.now() + 8000);
    expect(Date.parse(value.expiresAt)).toBeGreaterThan(Date.now());
  });
  it('rejects invalid, expired and BFF credentials at the native boundary', async () => {
    const now = Math.floor(Date.now() / 1000);
    const bff = await new SignJWT({
      act: { sub: 'community-bff' },
      scope: 'community:socket:issue',
      identityExpiresAt: now + 300,
    })
      .setProtectedHeader({ alg: 'RS256', kid: 'bff-test' })
      .setIssuer('community-bff')
      .setAudience('realtime-service')
      .setSubject(communityAlice)
      .setJti(communityAlice)
      .setIssuedAt(now)
      .setExpirationTime(now + 30)
      .sign(bffKey);
    const expired = await issueToken(identityKey, configuration, communityAlice, {
      expiresInSeconds: -10,
    });
    for (const invalid of ['synthetic-invalid', expired, bff])
      await http()
        .post(endpoint)
        .set('Authorization', 'Bearer ' + invalid)
        .send({})
        .expect(401);
    await http().post(endpoint).send({}).expect(401);
    expect(owner.calls).toHaveLength(0);
    const malformed = await http()
      .post(endpoint)
      .set('Authorization', 'Bearer ' + token)
      .set('Content-Type', 'application/json')
      .send('{')
      .expect(400);
    expect(malformed.body).toMatchObject({ code: 'VALIDATION_FAILED' });
    await http()
      .post(endpoint)
      .set('Authorization', 'Bearer ' + token)
      .set('Content-Type', 'text/plain')
      .send('synthetic-selector')
      .expect(400);
    await http()
      .post('/internal/v1/community/socket-credentials')
      .set('Authorization', 'Bearer ' + token)
      .expect(401);
    const response = await http()
      .post('/internal/v1/community/socket-credentials')
      .set('Authorization', 'Bearer ' + bff)
      .expect(200);
    expect(ticketSchema.safeParse(response.body).success).toBe(true);
  });
  it('rejects unsupported roles and closed request selectors before an owner query', async () => {
    for (const role of ['SPECIALIST', 'ADMIN'] as const) {
      const other = await issueToken(identityKey, configuration, communityAlice, { role });
      await http()
        .post(endpoint)
        .set('Authorization', 'Bearer ' + other)
        .send({})
        .expect(403);
    }
    for (const body of [
      { subject: communityAlice },
      { role: 'USER' },
      { identityExpiresAt: 9999999999 },
      [],
    ])
      await http()
        .post(endpoint)
        .set('Authorization', 'Bearer ' + token)
        .send(body)
        .expect(400);
    expect(owner.calls).toHaveLength(0);
  });
  it('fails closed on current owner denial and rechecks revocation at one-use consume', async () => {
    const value = await ticket();
    owner.denied.add(communityAlice);
    await http()
      .post(endpoint)
      .set('Authorization', 'Bearer ' + token)
      .expect(404);
    await expect(credentials.consume(value.credential)).rejects.toMatchObject({
      code: 'TARGET_UNAVAILABLE',
    });
    owner.denied.clear();
    await expect(credentials.consume(value.credential)).rejects.toThrow();
  });
  it('returns dependency failure without minting a ticket or caching a grant', async () => {
    owner.unavailable = true;
    await http()
      .post(endpoint)
      .set('Authorization', 'Bearer ' + token)
      .expect(503);
    expect(await redis.execute((client) => client.keys('community:v1:ticket:*'))).toEqual([]);
    owner.unavailable = false;
    expect((await ticket()).namespace).toBe('/community');
  });
  it('rejects an identity that expires during the current owner read', async () => {
    let release: (() => void) | undefined;
    owner.requestGate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const principal = {
      accountId: communityAlice,
      role: 'USER' as const,
      tokenId: 'synthetic',
      expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 1,
    };
    const pending = credentials.issueNative(principal);
    await new Promise((resolve) => setTimeout(resolve, 1100));
    release?.();
    await expect(pending).rejects.toMatchObject({ code: 'UNAUTHENTICATED' });
    expect(await redis.execute((client) => client.keys('community:v1:ticket:*'))).toEqual([]);
  });
  it('returns a safe 503 when real Redis is unavailable', async () => {
    await redis.onApplicationShutdown();
    await container.stop();
    stopped = true;
    const response = await http()
      .post(endpoint)
      .set('Authorization', 'Bearer ' + token)
      .expect(503);
    expect(response.body).toMatchObject({ code: 'DEPENDENCY_UNAVAILABLE' });
    expect(JSON.stringify(response.body)).not.toContain(token);
  });
});
