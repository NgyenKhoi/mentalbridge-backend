import type { INestApplication } from '@nestjs/common';
import { exportSPKI, generateKeyPair, SignJWT, type KeyLike } from 'jose';
import request from 'supertest';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { createApplication } from '../application.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import {
  ResourceBingoVersionMismatchError,
  ResourceBingoItemRetiredError,
  type ResourceEngagementRepository,
} from '../resources/resource-engagement.repository.js';
import type {
  ResourceBingoBoardState,
  ResourceBookmark,
} from '../resources/resource-engagement.types.js';
import { CareResourceActionsClient } from '../resources/care-resource-actions.client.js';
import { mondayOf, localDateInTimeZone } from '../resources/resource-journey.planner.js';

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
let care: CareResourceActionsClient;

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
        actionId: null,
        retired: false,
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
    syncPlanBingo: vi.fn(async () => ({ ...board, unavailableActions: [] })),
    completionEvidence: vi.fn(async () => null),
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
  care = {
    get: vi.fn(async () => ({ planState: 'NONE', actions: [] })),
  } as unknown as CareResourceActionsClient;

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
    careResourceActionsClient: care,
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
  it('syncs only the current local week from the owner-authorized Care projection', async () => {
    const timeZone = 'Asia/Ho_Chi_Minh';
    const today = localDateInTimeZone(new Date().toISOString(), timeZone);
    const currentWeek = mondayOf(today);
    const authorization = `Bearer ${await token()}`;
    const correlationId = '67000000-0000-4000-8000-000000000001';
    const target = `/api/v1/resource-bingo/${currentWeek}/plan-sync`;
    await request(app.getHttpServer()).put(target).send({ timeZone }).expect(401);
    await request(app.getHttpServer())
      .put(target)
      .set('Authorization', authorization)
      .send({ timeZone: 'invalid/timezone' })
      .expect(422);
    await request(app.getHttpServer())
      .put('/api/v1/resource-bingo/2020-01-06/plan-sync')
      .set('Authorization', authorization)
      .send({ timeZone })
      .expect(409);
    expect(care.get).not.toHaveBeenCalled();
    const result = await request(app.getHttpServer())
      .put(target)
      .set('Authorization', authorization)
      .set('X-Correlation-Id', correlationId)
      .send({ timeZone })
      .expect(200)
      .expect('ETag', '"0"');
    expect(result.body.unavailableActions).toEqual([]);
    expect(care.get).toHaveBeenCalledWith(authorization, today, correlationId);
    expect(repository.syncPlanBingo).toHaveBeenCalledWith(OWNER_A, currentWeek, timeZone, {
      planState: 'NONE',
      actions: [],
    });
  });

  it('leaves board state untouched when Care is unavailable', async () => {
    const weekStart = mondayOf(localDateInTimeZone(new Date().toISOString(), 'UTC'));
    vi.mocked(care.get).mockRejectedValueOnce(
      new (await import('@nestjs/common')).ServiceUnavailableException(),
    );
    await request(app.getHttpServer())
      .put(`/api/v1/resource-bingo/${weekStart}/plan-sync`)
      .set('Authorization', `Bearer ${await token()}`)
      .send({ timeZone: 'UTC' })
      .expect(503);
    expect(repository.syncPlanBingo).not.toHaveBeenCalled();
  });

  it('rejects self-confirmation for retired items with a stable conflict code', async () => {
    vi.mocked(repository.setBingoSelfConfirmed).mockRejectedValueOnce(
      new ResourceBingoItemRetiredError(),
    );
    const result = await request(app.getHttpServer())
      .put('/api/v1/resource-bingo/2026-10-12/items/1/self-confirmation')
      .set('Authorization', `Bearer ${await token()}`)
      .set('If-Match', '"0"')
      .send({ selfConfirmed: true })
      .expect(409);
    expect(result.body.code).toBe('RESOURCE_BINGO_ITEM_RETIRED');
  });

  it('returns only owner-scoped completion facts and distinguishes absence and outage', async () => {
    const target = `/api/v1/resource-completion-evidence?resourceId=${RESOURCE_ID}&resourceVersion=4&localDate=2026-10-21&timeZone=Asia%2FHo_Chi_Minh`;
    await request(app.getHttpServer()).get(target).expect(401);
    const authorization = `Bearer ${await token()}`;
    const missing = await request(app.getHttpServer())
      .get(target)
      .set('Authorization', authorization)
      .expect(404);
    expect(missing.body.code).toBe('RESOURCE_EVIDENCE_NOT_FOUND');
    const fact = {
      factKind: 'RESOURCE_COMPLETION' as const,
      factId: `${RESOURCE_ID}:2026-10-21`,
      resourceVersion: '4',
      occurredAt: '2026-10-21T03:00:00.000Z',
      verifiedAt: '2026-10-21T03:01:00.000Z',
    };
    vi.mocked(repository.completionEvidence).mockResolvedValueOnce(fact);
    const result = await request(app.getHttpServer())
      .get(target)
      .set('Authorization', authorization)
      .expect(200)
      .expect('Cache-Control', 'private, no-store');
    expect(result.body).toEqual(fact);
    expect(repository.completionEvidence).toHaveBeenCalledWith(
      OWNER_A,
      RESOURCE_ID,
      '4',
      '2026-10-21',
      'Asia/Ho_Chi_Minh',
      [],
    );
    const usedId = '77000000-0000-4000-8000-000000000001';
    await request(app.getHttpServer())
      .get(`${target}&excludedFactIds=${usedId}`)
      .set('Authorization', authorization)
      .expect(404);
    expect(repository.completionEvidence).toHaveBeenLastCalledWith(
      OWNER_A,
      RESOURCE_ID,
      '4',
      '2026-10-21',
      'Asia/Ho_Chi_Minh',
      [usedId],
    );
    await request(app.getHttpServer())
      .get(`${target}&excludedFactIds=${usedId}&excludedFactIds=${RESOURCE_ID}:2026-10-21`)
      .set('Authorization', authorization)
      .expect(404);
    expect(repository.completionEvidence).toHaveBeenLastCalledWith(
      OWNER_A,
      RESOURCE_ID,
      '4',
      '2026-10-21',
      'Asia/Ho_Chi_Minh',
      [usedId, `${RESOURCE_ID}:2026-10-21`],
    );
    for (const excludedFactIds of ['', 'x'.repeat(256)]) {
      await request(app.getHttpServer())
        .get(`${target}&excludedFactIds=${excludedFactIds}`)
        .set('Authorization', authorization)
        .expect(422);
    }
    await request(app.getHttpServer())
      .get(`${target}&ownerId=${OWNER_B}`)
      .set('Authorization', authorization)
      .expect(422);
    await request(app.getHttpServer())
      .get(target.replace('resourceVersion=4', 'resourceVersion=10000000000000000000'))
      .set('Authorization', authorization)
      .expect(422);
    vi.mocked(repository.completionEvidence).mockRejectedValueOnce(
      new Error('private database error'),
    );
    await request(app.getHttpServer()).get(target).set('Authorization', authorization).expect(503);
  });
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

  it('returns a persisted empty board and still distinguishes a missing week', async () => {
    board = { ...board, items: [] };
    const authorization = `Bearer ${await token()}`;
    const empty = await request(app.getHttpServer())
      .get('/api/v1/resource-bingo/2026-10-12')
      .set('Authorization', authorization)
      .expect(200)
      .expect('ETag', '"0"')
      .expect('Cache-Control', 'private, no-store');
    expect(empty.body).toMatchObject({
      weekStart: '2026-10-12',
      version: '0',
      items: [],
    });

    await request(app.getHttpServer())
      .get('/api/v1/resource-bingo/2026-10-19')
      .set('Authorization', authorization)
      .expect(404);
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
