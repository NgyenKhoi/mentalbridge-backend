import type { INestApplication } from '@nestjs/common';
import { exportSPKI, generateKeyPair, SignJWT, type KeyLike } from 'jose';
import request from 'supertest';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApplication } from '../application.js';
import { loadConfiguration } from '../configuration/configuration.js';
import type { NotificationPreferenceRepository } from '../notification-preferences/notification-preference.repository.js';
import type { ReminderOverviewRepository } from '../reminders/reminder-overview.repository.js';
import {
  overviewActivity,
  overviewNow,
  overviewOwner,
  overviewPreferences,
} from './reminder-overview.fixtures.js';

let app: INestApplication;
let privateKey: KeyLike;
let publicKey: string;
const issuer = 'https://identity.test.mentalbridge';
const preferences = vi.fn();
const activity = vi.fn();
const deliveries = vi.fn();

beforeAll(async () => {
  const keys = await generateKeyPair('RS256');
  privateKey = keys.privateKey;
  publicKey = await exportSPKI(keys.publicKey);
});
beforeEach(async () => {
  vi.clearAllMocks();
  const stored = overviewPreferences();
  preferences.mockResolvedValue({ ...stored, channels: { ...stored.channels, email: false } });
  activity.mockImplementation(async (ownerId: string) => ({
    ...overviewActivity(),
    achievementFacts: { ...overviewActivity().achievementFacts, ownerAccountId: ownerId },
  }));
  deliveries.mockResolvedValue({});
  app = await createApplication(
    loadConfiguration({
      NODE_ENV: 'test',
      DATABASE_URL: 'postgres://test:test@127.0.0.1:1/test',
      LOG_LEVEL: 'silent',
      IDENTITY_JWT_ISSUER: issuer,
      IDENTITY_JWT_AUDIENCE: 'mentalbridge-api',
      IDENTITY_JWT_PUBLIC_KEY: publicKey,
    }),
    {
      readinessProbe: { check: async () => undefined },
      notificationPreferenceRepository: {
        getOrCreate: preferences,
      } as unknown as NotificationPreferenceRepository,
      reminderActivityClient: { get: activity },
      reminderClock: { now: () => overviewNow },
      reminderOverviewRepository: { deliveries } as unknown as ReminderOverviewRepository,
    },
  );
  await app.init();
});
afterEach(async () => {
  await app?.close();
});
async function token(role = 'USER', subject = overviewOwner) {
  return new SignJWT({ roles: [role] })
    .setProtectedHeader({ alg: 'RS256' })
    .setSubject(subject)
    .setIssuer(issuer)
    .setAudience('mentalbridge-api')
    .setIssuedAt()
    .setExpirationTime('5m')
    .sign(privateKey);
}

describe('reminder overview HTTP boundary', () => {
  it('requires authentication before reading private preferences or facts', async () => {
    await request(app.getHttpServer()).get('/api/v1/reminder-overview').expect(401);
    expect(preferences).not.toHaveBeenCalled();
  });
  it.each(['SPECIALIST', 'ADMIN'])(
    'rejects %s without calling downstream sources',
    async (role) => {
      await request(app.getHttpServer())
        .get('/api/v1/reminder-overview')
        .set('Authorization', `Bearer ${await token(role)}`)
        .expect(403);
      expect(activity).not.toHaveBeenCalled();
    },
  );
  it('derives the owner from the JWT, forwards correlation and is non-cacheable', async () => {
    const correlation = '11111111-1111-4111-8111-111111111111';
    const result = await request(app.getHttpServer())
      .get('/api/v1/reminder-overview')
      .set('Authorization', `Bearer ${await token()}`)
      .set('X-Correlation-Id', correlation)
      .expect(200)
      .expect('Cache-Control', 'private, no-store');
    expect(preferences).toHaveBeenCalledWith(overviewOwner, 'USER');
    expect(activity).toHaveBeenCalledWith(overviewOwner, 'Asia/Ho_Chi_Minh', correlation);
    expect(result.body.today[0]).toMatchObject({ category: 'JOURNAL', status: 'DUE' });
    expect(result.body.today[1]).toMatchObject({
      category: 'EMOTION_CHECK_IN',
      status: 'COMPLETED',
    });
    expect(Object.keys(result.body).sort()).toEqual(
      [
        'schemaVersion',
        'asOf',
        'localDate',
        'timeZone',
        'preferences',
        'today',
        'previews',
        'unavailableCategories',
      ].sort(),
    );
    expect(JSON.stringify(result.body)).not.toContain(overviewOwner);
  });
  it.each(['ownerId=another-user', 'timezone=UTC', 'localDate=2026-10-21'])(
    'rejects an unsupported query %s',
    async (query) => {
      const result = await request(app.getHttpServer())
        .get(`/api/v1/reminder-overview?${query}`)
        .set('Authorization', `Bearer ${await token()}`)
        .expect(422);
      expect(result.body.code).toBe('REMINDER_QUERY_INVALID');
      expect(activity).not.toHaveBeenCalled();
    },
  );
  it('keeps different owners isolated', async () => {
    const other = 'b13e4567-e89b-42d3-a456-426614174000';
    await request(app.getHttpServer())
      .get('/api/v1/reminder-overview')
      .set('Authorization', `Bearer ${await token('USER', other)}`)
      .expect(200);
    expect(deliveries).toHaveBeenCalledWith(other, '2026-10-22');
    expect(preferences).not.toHaveBeenCalledWith(overviewOwner, 'USER');
  });
  it('returns safe Problem Details for dependency failure', async () => {
    activity.mockRejectedValue(new Error('private downstream payload'));
    const result = await request(app.getHttpServer())
      .get('/api/v1/reminder-overview')
      .set('Authorization', `Bearer ${await token()}`)
      .expect(503);
    expect(result.body).toMatchObject({ status: 503, code: 'REMINDER_OVERVIEW_UNAVAILABLE' });
    expect(result.body.correlationId).toBeTruthy();
    expect(JSON.stringify(result.body)).not.toContain('private downstream payload');
  });
});
