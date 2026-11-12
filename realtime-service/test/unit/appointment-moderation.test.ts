import { MongoServerError, ObjectId } from 'mongodb';
import { describe, expect, it, vi } from 'vitest';

import { AppointmentModerationService } from '../../src/moderation/appointment-moderation.service.js';
import type { StoredAppointmentReport } from '../../src/moderation/appointment-moderation.types.js';
import type { AuthenticatedPrincipal } from '../../src/security/principal.js';

const actor = '00000000-0000-4000-8000-000000000001';
const peer = '00000000-0000-4000-8000-000000000002';
const conversationId = '00000000-0000-4000-8000-000000000003';
const messageId = '00000000-0000-4000-8000-000000000004';
const reportId = '00000000-0000-4000-8000-000000000005';
const key = 'mb-694-command-0001';
const principal: AuthenticatedPrincipal = {
  accountId: actor,
  role: 'USER',
  tokenId: 'token',
  expiresAtEpochSeconds: 4_000_000_000,
};

function storedMessage(overrides: Record<string, unknown> = {}) {
  return {
    _id: new ObjectId(),
    messageId,
    conversationId,
    senderId: actor,
    clientMessageId: '00000000-0000-4000-8000-000000000006',
    type: 'TEXT' as const,
    bodyCiphertext: 'ciphertext',
    bodyIv: 'iv',
    bodyTag: 'tag',
    keyVersion: 'v1',
    commandFingerprint: 'fingerprint',
    sentAt: new Date('2026-11-11T00:00:00Z'),
    editedAt: null,
    deletedAt: null,
    moderationHold: false,
    schemaVersion: 1 as const,
    ...overrides,
  };
}

function report(overrides: Partial<StoredAppointmentReport> = {}): StoredAppointmentReport {
  const now = new Date('2026-11-11T00:00:00Z');
  return {
    _id: new ObjectId(),
    reportId,
    conversationId,
    messageId,
    reporterId: actor,
    reason: 'HARASSMENT',
    context: null,
    state: 'OPEN',
    resolution: null,
    resolvedBy: null,
    resolvedAt: null,
    createdAt: now,
    updatedAt: now,
    schemaVersion: 1,
    ...overrides,
  };
}

function setup() {
  const repository = {
    withTransaction: vi.fn(async (work: (session: object) => Promise<unknown>) => await work({})),
    findCommand: vi.fn().mockResolvedValue(null),
    insertCommand: vi.fn().mockResolvedValue(undefined),
    completeCommand: vi.fn().mockResolvedValue(undefined),
    findReport: vi.fn().mockResolvedValue(null),
    findReporterTarget: vi.fn().mockResolvedValue(null),
    insertReport: vi.fn().mockResolvedValue(true),
    listReports: vi.fn().mockResolvedValue([]),
    resolve: vi.fn(),
    insertAudit: vi.fn().mockResolvedValue(undefined),
  };
  const messages = {
    find: vi.fn().mockResolvedValue(storedMessage()),
    findInSession: vi.fn().mockResolvedValue(storedMessage()),
    tombstone: vi
      .fn()
      .mockImplementation((_conversation, _message, _sender, at) =>
        Promise.resolve(storedMessage({ deletedAt: at })),
      ),
    holdForModeration: vi.fn().mockResolvedValue(true),
  };
  const conversations = { bind: vi.fn().mockResolvedValue(undefined) };
  const encryption = {
    encrypt: vi.fn((content: string) => ({
      bodyCiphertext: content,
      bodyIv: 'iv',
      bodyTag: 'tag',
      keyVersion: 'v1',
    })),
    decrypt: vi.fn((body: { bodyCiphertext: string }) => body.bodyCiphertext),
  };
  const eligibility = {
    check: vi.fn().mockResolvedValue({ conversationId }),
  };
  const service = new AppointmentModerationService(
    repository as never,
    messages as never,
    conversations as never,
    encryption as never,
    eligibility,
  );
  return { service, repository, messages, conversations, encryption, eligibility };
}

describe('appointment message moderation', () => {
  it('authorizes through Consultation and creates an owner-only tombstone plus audit', async () => {
    const value = setup();
    const result = await value.service.removeOwn(
      'bearer',
      principal,
      conversationId,
      messageId,
      key,
      'correlation',
    );
    expect(result.state).toBe('DELETED');
    expect(value.eligibility.check).toHaveBeenCalledWith(
      'bearer',
      actor,
      conversationId,
      'delete',
      'correlation',
    );
    expect(value.messages.tombstone).toHaveBeenCalledWith(
      conversationId,
      messageId,
      actor,
      expect.any(Date),
      expect.anything(),
    );
    expect(value.repository.insertAudit).toHaveBeenCalledWith(
      expect.objectContaining({ actorId: actor, targetId: messageId, action: 'DELETE_MESSAGE' }),
      expect.anything(),
    );
  });

  it('fails closed when a participant attempts to delete a peer message', async () => {
    const value = setup();
    value.messages.findInSession.mockResolvedValue(storedMessage({ senderId: peer }));
    await expect(
      value.service.removeOwn('bearer', principal, conversationId, messageId, key, 'correlation'),
    ).rejects.toMatchObject({ status: 404 });
    expect(value.messages.tombstone).not.toHaveBeenCalled();
  });

  it('reports only an existing non-deleted message and encrypts bounded context', async () => {
    const value = setup();
    const result = await value.service.report(
      'bearer',
      principal,
      conversationId,
      messageId,
      key,
      'HARASSMENT',
      '  private context  ',
      'correlation',
    );
    expect(result).toMatchObject({ state: 'OPEN', duplicate: false });
    expect(value.eligibility.check).toHaveBeenCalledWith(
      'bearer',
      actor,
      conversationId,
      'report',
      'correlation',
    );
    expect(value.encryption.encrypt).toHaveBeenCalledWith('private context');
    expect(value.messages.holdForModeration).toHaveBeenCalledWith(
      conversationId,
      messageId,
      expect.anything(),
    );
    expect(value.repository.insertAudit).toHaveBeenCalledWith(
      expect.objectContaining({ reason: 'HARASSMENT', outcome: 'SUCCEEDED' }),
      expect.anything(),
    );
  });

  it('rejects report attempts against a deleted message', async () => {
    const value = setup();
    value.messages.findInSession.mockResolvedValue(storedMessage({ deletedAt: new Date() }));
    await expect(
      value.service.report(
        'bearer',
        principal,
        conversationId,
        messageId,
        key,
        'SPAM',
        undefined,
        'correlation',
      ),
    ).rejects.toMatchObject({ status: 404 });
    expect(value.repository.insertReport).not.toHaveBeenCalled();
  });

  it('does not expose reporter identity in an admin report listing', async () => {
    const value = setup();
    value.repository.listReports.mockResolvedValue([report()]);
    const result = await value.service.list({ ...principal, role: 'ADMIN' }, 'OPEN', 50);
    expect(result.items[0]).not.toHaveProperty('reporterId');
    await expect(value.service.list(principal, 'OPEN', 50)).rejects.toMatchObject({ status: 403 });
  });

  it('rejects conflicting reuse of an idempotency key', async () => {
    const value = setup();
    value.repository.insertCommand.mockRejectedValue(
      new MongoServerError({ errmsg: 'duplicate key', code: 11000 }),
    );
    value.repository.findCommand.mockResolvedValue({
      action: 'REPORT_MESSAGE',
      fingerprint: 'different',
      resultId: reportId,
      resultState: 'OPEN',
      resultTimestamp: new Date(),
      createdAt: new Date(),
    });
    await expect(
      value.service.report(
        'bearer',
        principal,
        conversationId,
        messageId,
        key,
        'SPAM',
        undefined,
        'correlation',
      ),
    ).rejects.toMatchObject({ status: 409, code: 'IDEMPOTENCY_CONFLICT' });
    expect(value.eligibility.check).toHaveBeenCalledWith(
      'bearer',
      actor,
      conversationId,
      'report',
      'correlation',
    );
  });
});
