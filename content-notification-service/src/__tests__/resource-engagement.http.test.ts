import type { INestApplication } from '@nestjs/common';
import { exportSPKI, generateKeyPair, SignJWT, type KeyLike } from 'jose';
import request from 'supertest';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { createApplication } from '../application.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import {
  ResourceBingoVersionMismatchError,
  type ResourceEngagementRepository,
} from '../resources/resource-engagement.repository.js';
import type {
  ResourceBingoBoardState,
  ResourceBookmark,
} from '../resources/resource-engagement.types.js';

const ISSUER = 'https://identity.local.mentalbridge';
const AUDIENCE = 'mentalbridge-api';
const OWNER_A = '66000000-0000-4000-8000-000000000001';
const OWNER_B = '66000000-0000-4000-8000-000000000002';
const RESOURCE_ID = '66000000-0000-4000-8000-000000000101';
let privateKey: KeyLike;
let publicKey: string;
let app: INestApplication;
let repository: ResourceEngagementRepository;
let bookmarks: Map<string, ResourceBookmark>;
let board: ResourceBingoBoardState;

beforeAll(async () => {
  const keys = await generateKeyPair('RS256');
  privateKey = keys.privateKey;
  publicKey = await exportSPKI(keys.publicKey);
});

beforeEach(async () => {
  bookmarks = new Map();
  board = {
    weekStart: '2026-10-12',
    timeZone: 'Asia/Ho_Chi_Minh',
    version: '0',
    updatedAt: '2026-10-12T00:00:00.000Z',
    items: [
      {
        position: 1,
        resourceId: RESOURCE_ID,
        contentVersion: '4',
        label: 'Nhịp thở dịu lại',
        evidence: 'NONE',
        selfConfirmedAt: null,
        verifiedAt: null,
        verificationSource: null,
      },
    ],
  };
  repository = {
    listBookmarks: vi.fn(async (ownerId: string) => {
      const item = bookmarks.get(ownerId);
      return item ? [item] : [];
    }),
    saveBookmark: vi.fn(async (ownerId: string, resourceId: string) => {
      if (resourceId !== RESOURCE_ID) return null;
      const item = bookmarks.get(ownerId) ?? {
        resourceId,
        contentVersion: '4',
        savedAt: '2026-10-12T00:00:00.000Z',
        version: '0',
      };
      bookmarks.set(ownerId, item);
      return item;
    }),
    deleteBookmark: vi.fn(async (ownerId: string, resourceId: string) => {
      if (bookmarks.get(ownerId)?.resourceId === resourceId) bookmarks.delete(ownerId);
    }),
    readBingo: vi.fn(async (ownerId: string, weekStart: string) =>
      ownerId === OWNER_A && weekStart === board.weekStart ? board : null,
    ),
    setBingoSelfConfirmed: vi.fn(
      async (
        ownerId: string,
        weekStart: string,
        position: number,
        expectedVersion: string,
        selfConfirmed: boolean,
      ) => {
        if (ownerId !== OWNER_A || weekStart !== board.weekStart || position !== 1) return null;
        if (expectedVersion !== board.version) throw new ResourceBingoVersionMismatchError();
        board = {
          ...board,
          version: String(Number(board.version) + 1),
          updatedAt: '2026-10-12T00:01:00.000Z',
          items: board.items.map((item) => ({
            ...item,
            evidence: selfConfirmed ? 'SELF_CONFIRMED' : 'NONE',
            selfConfirmedAt: selfConfirmed ? '2026-10-12T00:01:00.000Z' : null,
          })),
        };
        return board;
      },
    ),
  } as unknown as ResourceEngagementRepository;

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
    JOURNAL_AI_SERVICE_URL: 'http://localhost:3005',
    JOURNAL_AI_SERVICE_TIMEOUT_MS: 2_000,
    JOURNAL_AI_REMINDER_SERVICE_TOKEN: undefined,
    REMINDER_SCHEDULER_ENABLED: false,
    REMINDER_SCHEDULER_INTERVAL_MS: 60_000,
    REMINDER_SCHEDULER_BATCH_SIZE: 100,
  };
  app = await createApplication(configuration, {
    readinessProbe: { check: async () => undefined },
    resourceEngagementRepository: repository,
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

describe('resource engagement HTTP boundary', () => {
  it('requires authentication and keeps bookmarks owner scoped', async () => {
    await request(app.getHttpServer()).get('/api/v1/resource-bookmarks').expect(401);
    const authorization = `Bearer ${await token(OWNER_A)}`;
    const saved = await request(app.getHttpServer())
      .put(`/api/v1/resource-bookmarks/${RESOURCE_ID}`)
      .set('Authorization', authorization)
      .expect(200)
      .expect('Cache-Control', 'private, no-store');
    expect(saved.body).toMatchObject({ resourceId: RESOURCE_ID, contentVersion: '4' });

    const owner = await request(app.getHttpServer())
      .get('/api/v1/resource-bookmarks')
      .set('Authorization', authorization)
      .expect(200);
    expect(owner.body.items).toHaveLength(1);
    const other = await request(app.getHttpServer())
      .get('/api/v1/resource-bookmarks')
      .set('Authorization', `Bearer ${await token(OWNER_B)}`)
      .expect(200);
    expect(other.body.items).toEqual([]);

    await request(app.getHttpServer())
      .delete(`/api/v1/resource-bookmarks/${RESOURCE_ID}`)
      .set('Authorization', authorization)
      .expect(204);
    expect(bookmarks.has(OWNER_A)).toBe(false);
  });

  it('validates bookmark identifiers and unavailable resources', async () => {
    const authorization = `Bearer ${await token()}`;
    await request(app.getHttpServer())
      .put('/api/v1/resource-bookmarks/not-a-uuid')
      .set('Authorization', authorization)
      .expect(422);
    await request(app.getHttpServer())
      .put('/api/v1/resource-bookmarks/66000000-0000-4000-8000-000000000999')
      .set('Authorization', authorization)
      .expect(404);
  });

  it('returns ETags, enforces Monday week keys, and version-checks self-confirmation', async () => {
    const authorization = `Bearer ${await token()}`;
    await request(app.getHttpServer())
      .get('/api/v1/resource-bingo/2026-10-13')
      .set('Authorization', authorization)
      .expect(422);

    const initial = await request(app.getHttpServer())
      .get('/api/v1/resource-bingo/2026-10-12')
      .set('Authorization', authorization)
      .expect(200)
      .expect('ETag', '"0"')
      .expect('Cache-Control', 'private, no-store');
    expect(initial.body.items[0].evidence).toBe('NONE');

    const target = '/api/v1/resource-bingo/2026-10-12/items/1/self-confirmation';
    await request(app.getHttpServer())
      .put(target)
      .set('Authorization', authorization)
      .send({ selfConfirmed: true })
      .expect(428);
    await request(app.getHttpServer())
      .put(target)
      .set('Authorization', authorization)
      .set('If-Match', '0')
      .send({ selfConfirmed: true })
      .expect(400);
    await request(app.getHttpServer())
      .put(target)
      .set('Authorization', authorization)
      .set('If-Match', '"0"')
      .send({ selfConfirmed: true, fabricatedReward: true })
      .expect(422);

    const updated = await request(app.getHttpServer())
      .put(target)
      .set('Authorization', authorization)
      .set('If-Match', '"0"')
      .send({ selfConfirmed: true })
      .expect(200)
      .expect('ETag', '"1"');
    expect(updated.body.items[0]).toMatchObject({
      evidence: 'SELF_CONFIRMED',
      verificationSource: null,
    });

    await request(app.getHttpServer())
      .put(target)
      .set('Authorization', authorization)
      .set('If-Match', '"0"')
      .send({ selfConfirmed: false })
      .expect(412);
  });

  it('maps dependency failures without leaking details', async () => {
    vi.mocked(repository.listBookmarks).mockRejectedValueOnce(new Error('database secret'));
    const response = await request(app.getHttpServer())
      .get('/api/v1/resource-bookmarks')
      .set('Authorization', `Bearer ${await token()}`)
      .expect(503);
    expect(JSON.stringify(response.body)).not.toContain('database secret');
  });
});
