import { Inject, Injectable, ServiceUnavailableException } from '@nestjs/common';
import { CONFIGURATION_TOKEN } from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import {
  WellbeingRecipientUnavailableError,
  type EmailMessage,
  type WellbeingEmailDelivery,
  type WellbeingRecipientClient,
} from './wellbeing-digest.types.js';

@Injectable()
export class IdentityWellbeingRecipientClient implements WellbeingRecipientClient {
  constructor(@Inject(CONFIGURATION_TOKEN) private readonly config: ServiceConfiguration) {}

  async getEmail(ownerId: string, correlationId: string): Promise<string> {
    const token = this.config.IDENTITY_NOTIFICATION_SERVICE_TOKEN;
    if (!token) throw new ServiceUnavailableException();
    const url = new URL(
      `/internal/v1/notification-delivery-contacts/${encodeURIComponent(ownerId)}`,
      this.config.IDENTITY_SERVICE_URL,
    );
    const response = await fetch(url, {
      redirect: 'error',
      headers: {
        Accept: 'application/json',
        'X-MentalBridge-Service-Token': token,
        'X-Correlation-Id': correlationId,
      },
    });
    if (response.status === 404) throw new WellbeingRecipientUnavailableError();
    if (!response.ok) throw new ServiceUnavailableException();
    const body: unknown = await response.json();
    if (!body || typeof body !== 'object' || !('email' in body) || typeof body.email !== 'string') {
      throw new ServiceUnavailableException();
    }
    return body.email;
  }
}

@Injectable()
export class BrevoWellbeingEmailDelivery implements WellbeingEmailDelivery {
  constructor(@Inject(CONFIGURATION_TOKEN) private readonly config: ServiceConfiguration) {}

  async send(message: EmailMessage): Promise<{ providerMessageId: string | null }> {
    if (!this.config.BREVO_API_KEY || !this.config.BREVO_SENDER_EMAIL) {
      throw new ServiceUnavailableException();
    }
    const response = await fetch(new URL('/v3/smtp/email', this.config.BREVO_BASE_URL), {
      method: 'POST',
      redirect: 'error',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'api-key': this.config.BREVO_API_KEY,
      },
      body: JSON.stringify({
        sender: { email: this.config.BREVO_SENDER_EMAIL, name: this.config.BREVO_SENDER_NAME },
        to: [{ email: message.recipientEmail }],
        subject: message.subject,
        textContent: message.text,
        htmlContent: message.html,
      }),
    });
    if (!response.ok) throw new ServiceUnavailableException();
    const body: unknown = await response.json().catch(() => null);
    return {
      providerMessageId:
        body &&
        typeof body === 'object' &&
        'messageId' in body &&
        typeof body.messageId === 'string'
          ? body.messageId.slice(0, 160)
          : null,
    };
  }
}
