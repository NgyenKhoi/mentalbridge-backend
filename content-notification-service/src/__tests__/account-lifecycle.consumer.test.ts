import { describe, expect, it, vi } from 'vitest';

import {
  AccountLifecycleProjector,
  type AccountRegisteredEvent,
} from '../notification-preferences/account-lifecycle.consumer.js';

const event = (actorType: 'USER' | 'SPECIALIST'): AccountRegisteredEvent => ({
  messageId: '11111111-1111-4111-8111-111111111111',
  messageType: 'identity.account.registered',
  occurredAt: '2026-09-28T08:00:00.000Z',
  producer: 'identity-service',
  schemaVersion: '1.0',
  correlationId: '22222222-2222-4222-8222-222222222222',
  aggregateId: '33333333-3333-4333-8333-333333333333',
  aggregateVersion: 0,
  payload: {
    accountId: '33333333-3333-4333-8333-333333333333',
    actorType,
    status: 'PENDING_EMAIL_VERIFICATION',
  },
});

describe('AccountLifecycleProjector', () => {
  it('initializes the default preference aggregate for a registered user', async () => {
    const initializeForAccount = vi.fn().mockResolvedValue(undefined);
    const projector = new AccountLifecycleProjector({ initializeForAccount } as never);

    await expect(projector.project(event('USER'))).resolves.toBeUndefined();

    expect(initializeForAccount).toHaveBeenCalledOnce();
    expect(initializeForAccount).toHaveBeenCalledWith('33333333-3333-4333-8333-333333333333');
  });

  it('does not create user notification preferences for specialist accounts', async () => {
    const initializeForAccount = vi.fn().mockResolvedValue(undefined);
    const projector = new AccountLifecycleProjector({ initializeForAccount } as never);

    await expect(projector.project(event('SPECIALIST'))).resolves.toBeUndefined();

    expect(initializeForAccount).not.toHaveBeenCalled();
  });
});
