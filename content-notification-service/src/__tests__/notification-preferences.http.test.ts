import type { INestApplication } from '@nestjs/common';
import { exportSPKI, generateKeyPair, SignJWT, type KeyLike } from 'jose';
import request from 'supertest';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { createApplication } from '../application.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import {
  NotificationPreferenceVersionMismatchError,
  type NotificationPreferenceRepository,
} from '../notification-preferences/notification-preference.repository.js';
import type { NotificationPreferences } from '../notification-preferences/notification-preference.types.js';

const ISSUER = 'https://identity.local.mentalbridge';
const AUDIENCE = 'mentalbridge-api';
const OWNER_A = 'a13e4567-e89b-42d3-a456-426614174000';
const OWNER_B = 'b13e4567-e89b-42d3-a456-426614174000';
let privateKey: KeyLike;
let publicKey: string;
let app: INestApplication;

function defaults(): NotificationPreferences {
  return {
    notificationsEnabled: true,
    channels: { inApp: true, email: false, push: false },
    contentGroups: {
      journalReminder: true,
      emotionCheckIn: true,
      streakMilestone: true,
      screeningReassessment: true,
      appointmentMessage: true,
      resourceSystem: true,
    },
    quietHours: {
      enabled: false,
      start: '22:00',
      end: '07:00',
      timeZone: 'Asia/Ho_Chi_Minh',
    },
    email: {
      cadence: 'IMMEDIATE',
      wellbeingDigestEnabled: false,
      resourceRemindersEnabled: false,
    },
    version: 0,
    updatedAt: '2026-09-26T00:00:00.000Z',
  };
}

let records: Map<string, NotificationPreferences>;
let repository: NotificationPreferenceRepository;

beforeAll(async () => {
  const keys = await generateKeyPair('RS256');
  privateKey = keys.privateKey;
  publicKey = await exportSPKI(keys.publicKey);
});

beforeEach(async () => {
  records = new Map();
  repository = {
    getOrCreate: vi.fn(async (userId: string) => {
      const found = records.get(userId) ?? defaults();
      records.set(userId, found);
      return structuredClone(found);
    }),
    update: vi.fn(
      async (userId: string, expectedVersion: number, value: NotificationPreferences) => {
        const current = records.get(userId) ?? defaults();
        if (current.version !== expectedVersion)
          throw new NotificationPreferenceVersionMismatchError();
        const saved = {
          ...structuredClone(value),
          version: current.version + 1,
          updatedAt: '2026-09-26T00:01:00.000Z',
        };
        records.set(userId, saved);
        return saved;
      },
    ),
  } as unknown as NotificationPreferenceRepository;

  const configuration: ServiceConfiguration = {
    NODE_ENV: 'test',
    PORT: 3003,
    DATABASE_URL: 'postgres://test:test@localhost:5432/test',
    DB_POOL_MAX: 2,
    DB_IDLE_TIMEOUT_MS: 100,
    DB_CONNECT_TIMEOUT_MS: 100,
    LOG_LEVEL: 'silent',
    CORS_ORIGINS: '',
    SERVICE_NAME: 'content-notification-service',
    ALLOWED_ORIGINS: [],
    IDENTITY_JWT_ISSUER: ISSUER,
    IDENTITY_JWT_AUDIENCE: AUDIENCE,
    IDENTITY_JWT_PUBLIC_KEY: publicKey,
    IDENTITY_JWT_CLOCK_TOLERANCE_SECONDS: 0,
  };
  app = await createApplication(configuration, {
    readinessProbe: { check: async () => undefined },
    notificationPreferenceRepository: repository,
  });
  await app.init();
});

afterEach(async () => app.close());

async function token(subject = OWNER_A): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  return new SignJWT({ roles: ['USER'] })
    .setProtectedHeader({ alg: 'RS256' })
    .setSubject(subject)
    .setIssuer(ISSUER)
    .setAudience(AUDIENCE)
    .setIssuedAt(now)
    .setExpirationTime(now + 300)
    .sign(privateKey);
}

describe('notification preference HTTP boundary', () => {
  it('requires authentication and returns stable owner defaults without caching', async () => {
    await request(app.getHttpServer()).get('/api/v1/notification-preferences').expect(401);

    const response = await request(app.getHttpServer())
      .get('/api/v1/notification-preferences')
      .set('Authorization', `Bearer ${await token()}`)
      .expect(200)
      .expect('ETag', '"0"')
      .expect('Cache-Control', 'private, no-store');

    expect(response.body).toEqual(defaults());
    expect(repository.getOrCreate).toHaveBeenCalledWith(OWNER_A);
  });

  it('persists partial channel, group, quiet-hour, timezone, cadence and opt-in changes', async () => {
    const response = await request(app.getHttpServer())
      .patch('/api/v1/notification-preferences')
      .set('Authorization', `Bearer ${await token()}`)
      .set('If-Match', '"0"')
      .send({
        channels: { email: true, push: true },
        contentGroups: { journalReminder: false, resourceSystem: false },
        quietHours: {
          enabled: true,
          start: '21:30',
          end: '06:15',
          timeZone: 'Europe/Paris',
        },
        email: {
          cadence: 'DAILY_DIGEST',
          wellbeingDigestEnabled: true,
          resourceRemindersEnabled: true,
        },
      })
      .expect(200)
      .expect('ETag', '"1"');

    expect(response.body.channels).toEqual({ inApp: true, email: true, push: true });
    expect(response.body.contentGroups.journalReminder).toBe(false);
    expect(response.body.contentGroups.screeningReassessment).toBe(true);
    expect(response.body.quietHours).toEqual({
      enabled: true,
      start: '21:30',
      end: '06:15',
      timeZone: 'Europe/Paris',
    });
    expect(response.body.email).toEqual({
      cadence: 'DAILY_DIGEST',
      wellbeingDigestEnabled: true,
      resourceRemindersEnabled: true,
    });
  });

  it("keeps one owner's preferences inaccessible to another authenticated owner", async () => {
    await request(app.getHttpServer())
      .patch('/api/v1/notification-preferences')
      .set('Authorization', `Bearer ${await token(OWNER_A)}`)
      .set('If-Match', '"0"')
      .send({ channels: { email: true } })
      .expect(200);

    const other = await request(app.getHttpServer())
      .get('/api/v1/notification-preferences')
      .set('Authorization', `Bearer ${await token(OWNER_B)}`)
      .expect(200);

    expect(other.body.channels.email).toBe(false);
    expect(repository.getOrCreate).toHaveBeenLastCalledWith(OWNER_B);
  });

  it.each([
    [{ quietHours: { start: '24:30' } }, 'quietHours.start'],
    [{ quietHours: { enabled: true, start: '08:00', end: '08:00' } }, 'quietHours.end'],
    [{ quietHours: { timeZone: 'Not/AZone' } }, 'quietHours.timeZone'],
    [{ channels: { sms: true } }, 'channels'],
  ])('returns bounded validation details for invalid preferences', async (body, field) => {
    const response = await request(app.getHttpServer())
      .patch('/api/v1/notification-preferences')
      .set('Authorization', `Bearer ${await token()}`)
      .set('If-Match', '"0"')
      .send(body)
      .expect(422);

    expect(response.body.code).toBe('NOTIFICATION_PREFERENCE_VALIDATION_FAILED');
    expect(response.body.fieldViolations).toEqual(
      expect.arrayContaining([expect.objectContaining({ field: expect.stringContaining(field) })]),
    );
    expect(JSON.stringify(response.body)).not.toContain('Not/AZone');
  });

  it('requires a version and rejects stale updates', async () => {
    await request(app.getHttpServer())
      .patch('/api/v1/notification-preferences')
      .set('Authorization', `Bearer ${await token()}`)
      .send({ channels: { email: true } })
      .expect(428);

    records.set(OWNER_A, { ...defaults(), version: 2 });
    const response = await request(app.getHttpServer())
      .patch('/api/v1/notification-preferences')
      .set('Authorization', `Bearer ${await token()}`)
      .set('If-Match', '"1"')
      .send({ channels: { email: true } })
      .expect(412);

    expect(response.body.code).toBe('NOTIFICATION_PREFERENCE_VERSION_MISMATCH');
  });

  it('maps repository failure to a safe dependency response', async () => {
    vi.mocked(repository.getOrCreate).mockRejectedValueOnce(new Error('secret database details'));
    const response = await request(app.getHttpServer())
      .get('/api/v1/notification-preferences')
      .set('Authorization', `Bearer ${await token()}`)
      .expect(503);

    expect(response.body.code).toBe('DEPENDENCY_UNAVAILABLE');
    expect(JSON.stringify(response.body)).not.toContain('secret database details');
  });
});
