import type { INestApplication } from '@nestjs/common';
import { exportSPKI, generateKeyPair, SignJWT, type KeyLike } from 'jose';
import request from 'supertest';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { createApplication } from '../application.js';
import { AchievementPreferenceVersionMismatchError } from '../achievements/achievement.repository.js';
import type { AchievementRepository } from '../achievements/achievement.repository.js';
import type { AchievementVisibility } from '../achievements/achievement.types.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { ReminderActivityClient } from '../reminders/reminder-activity.client.js';

const ISSUER = 'https://identity.local.mentalbridge';
const AUDIENCE = 'mentalbridge-api';
const OWNER_A = 'a13e4567-e89b-42d3-a456-426614174000';
const OWNER_B = 'b13e4567-e89b-42d3-a456-426614174000';
let privateKey: KeyLike;
let publicKey: string;
let app: INestApplication;
let repository: AchievementRepository;
let activityClient: ReminderActivityClient;
let visibility: Map<string, { value: AchievementVisibility; version: number }>;

beforeAll(async () => {
  const keys = await generateKeyPair('RS256');
  privateKey = keys.privateKey;
  publicKey = await exportSPKI(keys.publicKey);
});

beforeEach(async () => {
  visibility = new Map();
  repository = {
    read: vi.fn(async (ownerId: string) => ({
      facts:
        ownerId === OWNER_A
          ? [
              {
                kind: 'RESOURCE_COMPLETION' as const,
                resourceId: '123e4567-e89b-42d3-a456-426614174000',
                contentVersion: '4',
                localDate: '2026-10-12',
                occurredAt: '2026-10-12T05:00:00.000Z',
              },
            ]
          : [],
      preference: {
        visibilityState: visibility.get(ownerId)?.value ?? 'VISIBLE',
        version: String(visibility.get(ownerId)?.version ?? 0),
      },
    })),
    setVisibility: vi.fn(
      async (ownerId: string, value: AchievementVisibility, expectedVersion: string) => {
        const current = visibility.get(ownerId) ?? { value: 'VISIBLE' as const, version: 0 };
        if (String(current.version) !== expectedVersion) {
          throw new AchievementPreferenceVersionMismatchError();
        }
        visibility.set(ownerId, { value, version: current.version + 1 });
      },
    ),
  } as unknown as AchievementRepository;
  activityClient = {
    get: vi.fn(async (ownerAccountId: string, timezone: string) => ({
      asOfLocalDate: '2026-10-13',
      timezone,
      journal: { completedToday: false, currentStreak: 0, longestStreak: 3 },
      emotionCheckIn: { completedToday: false, currentStreak: 0, longestStreak: 1 },
      achievementFacts: {
        ownerAccountId,
        journal: {
          distinctDays: 3,
          longestStreak: 3,
          firstOccurredAt: '2026-10-01T01:00:00.000Z',
          streakMilestones: [
            { days: 3, reachedAt: '2026-10-03T01:00:00.000Z' },
            { days: 7, reachedAt: null },
          ],
        },
        emotionCheckIn: {
          distinctDays: 1,
          longestStreak: 1,
          firstOccurredAt: '2026-10-02T01:00:00.000Z',
          streakMilestones: [
            { days: 3, reachedAt: null },
            { days: 7, reachedAt: null },
          ],
        },
      },
      interpretation: 'FACTUAL_ACTIVITY_NOT_ADHERENCE_OR_RECOVERY' as const,
    })),
  };
  const configuration = {
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
    JOURNAL_AI_SERVICE_URL: 'http://localhost:3005',
    JOURNAL_AI_SERVICE_TIMEOUT_MS: 2_000,
    JOURNAL_AI_REMINDER_SERVICE_TOKEN: undefined,
    REMINDER_SCHEDULER_ENABLED: false,
    REMINDER_SCHEDULER_INTERVAL_MS: 60_000,
    REMINDER_SCHEDULER_BATCH_SIZE: 100,
  } as ServiceConfiguration;
  app = await createApplication(configuration, {
    readinessProbe: { check: async () => undefined },
    achievementRepository: repository,
    reminderActivityClient: activityClient,
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

describe('achievement HTTP boundary', () => {
  it('requires authentication and keeps facts owner-bound', async () => {
    await request(app.getHttpServer()).get('/api/v1/achievements?timezone=UTC').expect(401);
    const owner = await request(app.getHttpServer())
      .get('/api/v1/achievements?timezone=Asia%2FHo_Chi_Minh')
      .set('Authorization', `Bearer ${await token()}`)
      .expect(200)
      .expect('ETag', '"0"')
      .expect('Cache-Control', 'private, no-store');
    expect(owner.body.items).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ definitionId: 'journal.first-entry', status: 'EARNED' }),
        expect.objectContaining({ definitionId: 'resource.first-completion', status: 'EARNED' }),
        expect.objectContaining({ definitionId: 'journey.new-chapter', status: 'DEFERRED' }),
      ]),
    );
    await request(app.getHttpServer())
      .get('/api/v1/achievements?timezone=UTC')
      .set('Authorization', `Bearer ${await token(OWNER_B)}`)
      .expect(200);
    expect(repository.read).toHaveBeenCalledWith(OWNER_B);
    expect(activityClient.get).toHaveBeenCalledWith(OWNER_B, 'UTC', expect.any(String));
  });

  it('fails a wrong-owner activity response closed without hiding resource truth', async () => {
    const foreign = await activityClient.get(OWNER_B, 'UTC', 'fixture');
    vi.mocked(activityClient.get).mockResolvedValueOnce(foreign);
    const response = await request(app.getHttpServer())
      .get('/api/v1/achievements?timezone=UTC')
      .set('Authorization', `Bearer ${await token()}`)
      .expect(200);
    expect(response.body.sources).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ domain: 'JOURNAL', availability: 'UNAVAILABLE' }),
        expect.objectContaining({ domain: 'RESOURCE', availability: 'AVAILABLE' }),
      ]),
    );
    expect(response.body.items).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          definitionId: 'journal.first-entry',
          status: 'SOURCE_UNAVAILABLE',
        }),
        expect.objectContaining({ definitionId: 'resource.first-completion', status: 'EARNED' }),
      ]),
    );
  });

  it('fails a wrong-timezone activity response closed', async () => {
    const mismatched = await activityClient.get(OWNER_A, 'UTC', 'fixture');
    vi.mocked(activityClient.get).mockResolvedValueOnce({
      ...mismatched,
      timezone: 'Asia/Ho_Chi_Minh',
    });
    const response = await request(app.getHttpServer())
      .get('/api/v1/achievements?timezone=UTC')
      .set('Authorization', `Bearer ${await token()}`)
      .expect(200);
    expect(response.body.items).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          definitionId: 'emotion.first-check-in',
          status: 'SOURCE_UNAVAILABLE',
        }),
      ]),
    );
  });

  it('updates privacy with optimistic concurrency and preserves achievement facts', async () => {
    const authorization = `Bearer ${await token()}`;
    const changed = await request(app.getHttpServer())
      .put('/api/v1/achievements/preference?timezone=UTC')
      .set('Authorization', authorization)
      .set('If-Match', '"0"')
      .send({ visibilityState: 'HIDDEN' })
      .expect(200)
      .expect('ETag', '"1"');
    expect(changed.body).toMatchObject({ visibilityState: 'HIDDEN', preferenceVersion: '1' });
    expect(changed.body.items).toEqual(
      expect.arrayContaining([expect.objectContaining({ status: 'EARNED' })]),
    );
    await request(app.getHttpServer())
      .put('/api/v1/achievements/preference?timezone=UTC')
      .set('Authorization', authorization)
      .set('If-Match', '"0"')
      .send({ visibilityState: 'VISIBLE' })
      .expect(412);
  });

  it('validates timezone, preference shape, and version preconditions', async () => {
    const authorization = `Bearer ${await token()}`;
    await request(app.getHttpServer())
      .get('/api/v1/achievements?timezone=Not_A_Timezone')
      .set('Authorization', authorization)
      .expect(422);
    await request(app.getHttpServer())
      .put('/api/v1/achievements/preference?timezone=UTC')
      .set('Authorization', authorization)
      .send({ visibilityState: 'HIDDEN' })
      .expect(428);
    await request(app.getHttpServer())
      .put('/api/v1/achievements/preference?timezone=UTC')
      .set('Authorization', authorization)
      .set('If-Match', '"0"')
      .send({ visibilityState: 'HIDDEN', clinicalScore: 8 })
      .expect(422);
  });
});
