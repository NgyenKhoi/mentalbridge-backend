import { afterEach, describe, expect, it, vi } from 'vitest';

import { ConsultationConversationEvidence } from '../../src/conversations/conversation-evidence.js';
import { testConfiguration } from '../fixtures/test-configuration.js';

const appointmentId = '22222222-2222-4222-8222-222222222222';
const evidenceId = '11111111-1111-4111-8111-111111111111';
const messageId = '33333333-3333-4333-8333-333333333333';

describe('ConsultationConversationEvidence', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('sends accepted-message metadata without raw chat content', async () => {
    const fetch = vi.fn<(input: URL, init?: RequestInit) => Promise<Response>>();
    fetch.mockResolvedValue(
      new Response(
        JSON.stringify({
          evidenceId,
          accepted: true,
          duplicate: false,
          reasonCode: 'EVIDENCE_ACCEPTED',
          receivedAt: '2026-10-01T02:01:01Z',
        }),
        { status: 200, headers: { 'content-type': 'application/json' } },
      ),
    );
    vi.stubGlobal('fetch', fetch);

    await expect(
      service().record(
        'identity-token',
        appointmentId,
        {
          evidenceId,
          type: 'ACCEPTED_MESSAGE',
          occurredAt: '2026-10-01T02:01:00Z',
          messageId,
        },
        'correlation-1',
      ),
    ).resolves.toBeUndefined();

    const request = fetch.mock.calls[0]?.[1];
    const requestBody = request?.body;
    expect(typeof requestBody).toBe('string');
    if (typeof requestBody !== 'string') throw new Error('Expected a JSON request body');
    expect(JSON.parse(requestBody)).toEqual({
      evidenceId,
      type: 'ACCEPTED_MESSAGE',
      occurredAt: '2026-10-01T02:01:00Z',
      messageId,
    });
    expect(new Headers(request?.headers).get('x-mentalbridge-service-token')).toBe(
      testConfiguration('unused-public-key').CONSULTATION_EVIDENCE_SERVICE_TOKEN,
    );
    expect(requestBody).not.toContain('content');
  });

  it('fails retryably when the evidence owner is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));

    await expect(
      service().record(
        'identity-token',
        appointmentId,
        { evidenceId, type: 'CHECK_IN', occurredAt: '2026-10-01T02:00:00Z' },
        'correlation-1',
      ),
    ).rejects.toMatchObject({ code: 'CHAT_EVIDENCE_UNAVAILABLE', retryable: true });
  });
});

function service() {
  return new ConsultationConversationEvidence(testConfiguration('unused-public-key'));
}
