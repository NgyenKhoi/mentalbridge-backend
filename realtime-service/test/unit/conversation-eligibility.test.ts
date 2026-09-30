import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  ConsultationConversationEligibility,
  type ConversationEligibilityDecision,
} from '../../src/conversations/conversation-eligibility.js';
import { testConfiguration } from '../fixtures/test-configuration.js';

const conversationId = '22222222-2222-4222-8222-222222222222';
const userId = '11111111-1111-4111-8111-111111111111';
const specialistId = '44444444-4444-4444-8444-444444444444';

const active: ConversationEligibilityDecision = {
  conversationId,
  appointmentId: conversationId,
  userAccountId: userId,
  specialistAccountId: specialistId,
  phase: 'ACTIVE',
  reasonCode: 'APPOINTMENT_ACTIVE',
  subscribeAllowed: true,
  sendAllowed: true,
  historyAllowed: true,
  scheduledStartAt: '2026-09-29T02:00:00.000Z',
  scheduledEndAt: '2026-09-29T03:00:00.000Z',
  serverTime: '2026-09-29T02:30:00.000Z',
};

describe('ConsultationConversationEligibility', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('forwards the bearer and operation to Consultation', async () => {
    const fetch = respond(active);
    const eligibility = service();

    await expect(
      eligibility.check('identity-token', userId, conversationId, 'send', 'correlation-1'),
    ).resolves.toEqual(active);

    expect(fetch).toHaveBeenCalledOnce();
    expect(fetch.mock.calls[0]?.[0]).toEqual(
      new URL(
        `http://consultation.test/internal/v1/appointments/${conversationId}/chat-eligibility?operation=SEND`,
      ),
    );
    expect(fetch.mock.calls[0]?.[1]?.headers).toEqual({
      authorization: 'Bearer identity-token',
      'x-correlation-id': 'correlation-1',
    });
  });

  it.each([
    ['WAITING', 'APPOINTMENT_WAITING', 'CHAT_NOT_STARTED'],
    ['ENDED', 'APPOINTMENT_ENDED', 'CHAT_ENDED'],
    ['CANCELLED', 'APPOINTMENT_CANCELLED', 'CHAT_CANCELLED'],
    ['RESCHEDULED', 'APPOINTMENT_RESCHEDULED', 'CHAT_RESCHEDULED'],
  ] as const)('rejects SEND during %s with %s', async (phase, reasonCode, expectedCode) => {
    respond({
      ...active,
      phase,
      reasonCode,
      subscribeAllowed: phase === 'WAITING',
      sendAllowed: false,
    });

    await expect(
      service().check('identity-token', userId, conversationId, 'send', 'correlation-1'),
    ).rejects.toMatchObject({ code: expectedCode, retryable: false });
  });

  it('rejects a response that does not bind the current actor', async () => {
    respond({ ...active, userAccountId: '55555555-5555-4555-8555-555555555555' });

    await expect(
      service().check('identity-token', userId, conversationId, 'history', 'correlation-1'),
    ).rejects.toMatchObject({ code: 'ACCESS_DENIED' });
  });

  it('fails closed when Consultation is unavailable or malformed', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    await expect(
      service().check('identity-token', userId, conversationId, 'send', 'correlation-1'),
    ).rejects.toMatchObject({ code: 'CHAT_ELIGIBILITY_UNAVAILABLE', retryable: true });

    respond({ ...active, conversationId: '55555555-5555-4555-8555-555555555555' });
    await expect(
      service().check('identity-token', userId, conversationId, 'send', 'correlation-1'),
    ).rejects.toMatchObject({ code: 'CHAT_ELIGIBILITY_UNAVAILABLE', retryable: true });
  });
});

function service() {
  return new ConsultationConversationEligibility(testConfiguration('unused-public-key'));
}

function respond(decision: ConversationEligibilityDecision) {
  const fetch = vi.fn<(input: URL, init?: RequestInit) => Promise<Response>>();
  fetch.mockResolvedValue(
    new Response(JSON.stringify(decision), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    }),
  );
  vi.stubGlobal('fetch', fetch);
  return fetch;
}
