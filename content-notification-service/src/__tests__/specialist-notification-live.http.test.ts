import type { INestApplication } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import { exportSPKI, generateKeyPair, SignJWT, type KeyLike } from 'jose';
import request from 'supertest';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { createApplication } from '../application.js';
import { loadConfiguration } from '../configuration/configuration.js';

describe('specialist notification live eligibility HTTP authorization', () => {
  let app: INestApplication;
  let privateKey: KeyLike;
  let publicKey: string;
  const notificationId = randomUUID();
  const owner = randomUUID();
  const eligible = vi.fn();
  async function token(subject = owner, roles = ['SPECIALIST'], expired = false) {
    return new SignJWT({ roles })
      .setProtectedHeader({ alg: 'RS256' })
      .setSubject(subject)
      .setIssuer('synthetic-identity')
      .setAudience('synthetic-api')
      .setIssuedAt()
      .setExpirationTime(expired ? Math.floor(Date.now() / 1000) - 60 : '5m')
      .sign(privateKey);
  }
  beforeAll(async () => {
    const keys = await generateKeyPair('RS256');
    privateKey = keys.privateKey;
    publicKey = await exportSPKI(keys.publicKey);
  });
  beforeEach(async () => {
    eligible.mockReset();
    eligible.mockImplementation(async (_id: string, account: string) =>
      account === owner ? notificationId : null,
    );
    app = await createApplication(
      loadConfiguration({
        NODE_ENV: 'test',
        DATABASE_URL: 'postgres://synthetic:synthetic@127.0.0.1:1/synthetic',
        IDENTITY_JWT_ISSUER: 'synthetic-identity',
        IDENTITY_JWT_AUDIENCE: 'synthetic-api',
        IDENTITY_JWT_PUBLIC_KEY: publicKey,
        IDENTITY_JWT_CLOCK_TOLERANCE_SECONDS: '0',
        LOG_LEVEL: 'silent',
      }),
      { specialistNotificationLiveRepository: { eligible } as never },
    );
    await app.init();
  });
  afterEach(async () => {
    await app?.close();
  });
  const path = () => '/internal/v1/notifications/' + notificationId + '/live-eligibility';

  it('returns only a non-cacheable eligible pointer and ignores client recipient input', async () => {
    const response = await request(app.getHttpServer())
      .get(path() + '?recipientAccountId=' + randomUUID())
      .auth(await token(), { type: 'bearer' })
      .expect(200);
    expect(response.body).toEqual({ notificationId });
    expect(response.headers['cache-control']).toBe('private, no-store');
    expect(eligible).toHaveBeenCalledWith(notificationId, owner);
  });

  it('rejects USER and ADMIN roles before any owner query', async () => {
    for (const role of ['USER', 'ADMIN'])
      await request(app.getHttpServer())
        .get(path())
        .auth(await token(owner, [role]), { type: 'bearer' })
        .expect(403);
    expect(eligible).not.toHaveBeenCalled();
  });

  it('requires the exact SPECIALIST role instead of accepting mixed-role credentials', async () => {
    await request(app.getHttpServer())
      .get(path())
      .auth(await token(owner, ['USER', 'SPECIALIST']), { type: 'bearer' })
      .expect(403);
    expect(eligible).not.toHaveBeenCalled();
  });

  it('rejects missing and expired Identity credentials before any owner query', async () => {
    const absent = await request(app.getHttpServer()).get(path()).expect(401);
    expect(absent.headers['cache-control']).toBe('private, no-store');
    await request(app.getHttpServer())
      .get(path())
      .auth(await token(owner, ['SPECIALIST'], true), { type: 'bearer' })
      .expect(401);
    expect(eligible).not.toHaveBeenCalled();
  });

  it('uses uniform 404 for foreign/absent/suppressed rows and validates UUIDs', async () => {
    const foreign = await request(app.getHttpServer())
      .get(path())
      .auth(await token(randomUUID()), { type: 'bearer' })
      .expect(404);
    eligible.mockResolvedValueOnce(null);
    const missing = await request(app.getHttpServer())
      .get(path())
      .auth(await token(), { type: 'bearer' })
      .expect(404);
    expect(foreign.body.code).toBe('NOTIFICATION_NOT_FOUND');
    expect(missing.body.code).toBe(foreign.body.code);
    await request(app.getHttpServer())
      .get('/internal/v1/notifications/invalid/live-eligibility')
      .auth(await token(), { type: 'bearer' })
      .expect(422);
  });

  it('fails closed without exposing storage details or tokens', async () => {
    eligible.mockRejectedValueOnce(new Error('private-row-and-bearer-must-not-leak'));
    const bearer = await token();
    const response = await request(app.getHttpServer())
      .get(path())
      .auth(bearer, { type: 'bearer' })
      .expect(503);
    expect(response.headers['cache-control']).toBe('private, no-store');
    expect(JSON.stringify(response.body)).not.toContain('private-row-and-bearer-must-not-leak');
    expect(JSON.stringify(response.body)).not.toContain(bearer);
  });
});
