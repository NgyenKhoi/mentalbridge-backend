import { describe, expect, it, vi } from 'vitest';

import type { NotificationRepository } from '../notifications/notification.repository.js';
import { NotificationService } from '../notifications/notification.service.js';

const ownerId = 'a13e4567-e89b-42d3-a456-426614174000';
const resourceId = 'b13e4567-e89b-42d3-a456-426614174000';

function service() {
  const repository = { create: vi.fn() } as unknown as NotificationRepository;
  return { repository, subject: new NotificationService(repository) };
}

function command() {
  return {
    ownerId,
    kind: 'SYSTEM_RESOURCE',
    title: 'Tài nguyên mới',
    body: 'Một tài nguyên đã được cập nhật.',
    occurredAt: new Date().toISOString(),
    action: { type: 'OPEN_RESOURCE', targetId: resourceId },
    source: 'CONTENT',
    sourceIdentity: `resource:${resourceId}:0`,
    priority: 'NORMAL',
  };
}

describe('NotificationService producer boundary', () => {
  it('accepts only approved action types and passes a stable fingerprint to persistence', async () => {
    const { repository, subject } = service();
    vi.mocked(repository.create).mockResolvedValue({ id: 'saved' } as never);
    const payload = command();

    await subject.create(payload);
    await subject.create(payload);

    expect(repository.create).toHaveBeenCalledTimes(2);
    expect(vi.mocked(repository.create).mock.calls[0][1]).toMatch(/^[0-9a-f]{64}$/);
    expect(vi.mocked(repository.create).mock.calls[1][1]).toBe(
      vi.mocked(repository.create).mock.calls[0][1],
    );
  });

  it.each([
    { ...command(), externalUrl: 'https://untrusted.example' },
    { ...command(), action: { type: 'OPEN_RESOURCE' } },
    { ...command(), action: { type: 'OPEN_MESSAGES', targetId: resourceId } },
    { ...command(), action: { type: 'OPEN_EXTERNAL', targetId: resourceId } },
    { ...command(), journalText: 'raw journal content' },
  ])('rejects malformed actions and unexpected sensitive producer fields', async (payload) => {
    const { repository, subject } = service();
    await expect(subject.create(payload)).rejects.toMatchObject({ status: 422 });
    expect(repository.create).not.toHaveBeenCalled();
  });
});
