import { ServiceUnavailableException } from '@nestjs/common';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import {
  BrevoWellbeingEmailDelivery,
  IdentityWellbeingRecipientClient,
} from '../wellbeing-digest/wellbeing-delivery.clients.js';
import { WellbeingRecipientUnavailableError } from '../wellbeing-digest/wellbeing-digest.types.js';

const configuration = {
  IDENTITY_SERVICE_URL: 'http://identity.test:8081',
  IDENTITY_NOTIFICATION_SERVICE_TOKEN: 'test-notification-token-at-least-32-characters',
  BREVO_BASE_URL: 'https://api.brevo.test',
  BREVO_API_KEY: 'brevo-test-key',
  BREVO_SENDER_EMAIL: 'care@example.test',
  BREVO_SENDER_NAME: 'MentalBridge',
} as ServiceConfiguration;

afterEach(() => vi.unstubAllGlobals());

describe('wellbeing delivery clients', () => {
  it('resolves only the scoped delivery email and maps an absent owner to ineligible', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ email: 'owner@example.test' }))
      .mockResolvedValueOnce(new Response(null, { status: 404 }));
    vi.stubGlobal('fetch', fetch);
    const client = new IdentityWellbeingRecipientClient(configuration);

    await expect(client.getEmail('owner-1', 'correlation')).resolves.toBe('owner@example.test');
    await expect(client.getEmail('owner-2', 'correlation')).rejects.toBeInstanceOf(
      WellbeingRecipientUnavailableError,
    );
    expect(new Headers(fetch.mock.calls[0]?.[1]?.headers).get('x-mentalbridge-service-token')).toBe(
      configuration.IDENTITY_NOTIFICATION_SERVICE_TOKEN,
    );
  });

  it('sends only approved rendered content to Brevo and bounds the provider id', async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json({ messageId: 'p'.repeat(200) }));
    vi.stubGlobal('fetch', fetch);

    const result = await new BrevoWellbeingEmailDelivery(configuration).send({
      recipientEmail: 'owner@example.test',
      subject: 'Những bước nhỏ hôm nay',
      text: 'Một lời nhắc dịu dàng.',
      html: '<p>Một lời nhắc dịu dàng.</p>',
    });

    const [url, request] = fetch.mock.calls[0] as [URL, RequestInit];
    expect(url.toString()).toBe('https://api.brevo.test/v3/smtp/email');
    expect(JSON.parse(String(request.body))).toEqual({
      sender: { email: 'care@example.test', name: 'MentalBridge' },
      to: [{ email: 'owner@example.test' }],
      subject: 'Những bước nhỏ hôm nay',
      textContent: 'Một lời nhắc dịu dàng.',
      htmlContent: '<p>Một lời nhắc dịu dàng.</p>',
    });
    expect(result.providerMessageId).toHaveLength(160);
  });

  it('fails closed when Brevo is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 503 })));
    await expect(
      new BrevoWellbeingEmailDelivery(configuration).send({
        recipientEmail: 'owner@example.test',
        subject: 'subject',
        text: 'text',
        html: '<p>text</p>',
      }),
    ).rejects.toBeInstanceOf(ServiceUnavailableException);
  });
});
