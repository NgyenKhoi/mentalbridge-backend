import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  BrevoAppointmentEmailDelivery,
  PermanentProviderRejectionError,
  TransientProviderRejectionError,
  UnknownProviderOutcomeError,
} from '../appointment-reminders/appointment-email.delivery.js';

const message = {
  recipient: 'synthetic.user@example.test',
  subject: 'Nhắc lịch hẹn MentalBridge',
  text: 'Safe appointment text',
  html: '<p>Safe appointment text</p>',
  idempotencyKey: '10000000-0000-5000-8000-000000000004',
  correlationId: '10000000-0000-4000-8000-000000000005',
};

function adapter() {
  return new BrevoAppointmentEmailDelivery({
    BREVO_BASE_URL: 'https://api.brevo.test',
    BREVO_API_KEY: 'synthetic-key',
    BREVO_SENDER_EMAIL: 'no-reply@example.test',
    BREVO_SENDER_NAME: 'MentalBridge',
    APPOINTMENT_REMINDER_HTTP_TIMEOUT_MS: 2_000,
  } as never);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Brevo appointment email adapter', () => {
  it('sends one minimized message with the stable provider key and tracking consent disabled', async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json({ messageId: 'provider-message-1' }));
    vi.stubGlobal('fetch', fetch);
    const result = await adapter().send(message);

    expect(result).toBe('provider-message-1');
    const [, request] = fetch.mock.calls[0] as [URL, RequestInit];
    const body = JSON.parse(String(request.body)) as {
      to: { email: string; contactPixelTrackingConsent: boolean }[];
      headers: Record<string, string>;
    };
    expect(body.to).toEqual([
      { email: 'synthetic.user@example.test', contactPixelTrackingConsent: false },
    ]);
    expect(body.headers).toMatchObject({
      idempotencyKey: '10000000-0000-5000-8000-000000000004',
      'X-Correlation-Id': '10000000-0000-4000-8000-000000000005',
    });
    expect(JSON.stringify(body)).not.toMatch(/journal|assessment|brief|chat content/i);
  });

  it.each([
    [429, TransientProviderRejectionError],
    [500, TransientProviderRejectionError],
    [400, PermanentProviderRejectionError],
  ])('classifies provider HTTP %i without exposing a response body', async (status, error) => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('private provider details', { status })),
    );
    await expect(adapter().send(message)).rejects.toBeInstanceOf(error);
  });

  it('treats a lost provider response as an unknown outcome for bounded retry', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('private network details')));
    await expect(adapter().send(message)).rejects.toBeInstanceOf(UnknownProviderOutcomeError);
  });
});
