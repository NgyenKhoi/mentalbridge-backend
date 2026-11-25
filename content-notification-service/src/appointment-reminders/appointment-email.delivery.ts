import { Inject, Injectable } from '@nestjs/common';

import { CONFIGURATION_TOKEN } from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';

export interface AppointmentEmailMessage {
  readonly recipient: string;
  readonly subject: string;
  readonly text: string;
  readonly html: string;
  readonly idempotencyKey: string;
  readonly correlationId: string;
}

export interface AppointmentEmailDelivery {
  send(message: AppointmentEmailMessage): Promise<string>;
}

export class UnknownProviderOutcomeError extends Error {}
export class TransientProviderRejectionError extends Error {}
export class PermanentProviderRejectionError extends Error {}

@Injectable()
export class BrevoAppointmentEmailDelivery implements AppointmentEmailDelivery {
  constructor(@Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration) {}

  async send(message: AppointmentEmailMessage): Promise<string> {
    const controller = new AbortController();
    const timeout = setTimeout(() => {
      controller.abort();
    }, this.configuration.APPOINTMENT_REMINDER_HTTP_TIMEOUT_MS);
    let result: Response;
    try {
      result = await fetch(new URL('/v3/smtp/email', this.configuration.BREVO_BASE_URL), {
        method: 'POST',
        headers: {
          'api-key': this.configuration.BREVO_API_KEY ?? '',
          'content-type': 'application/json',
        },
        body: JSON.stringify({
          sender: {
            name: this.configuration.BREVO_SENDER_NAME,
            email: this.configuration.BREVO_SENDER_EMAIL,
          },
          to: [{ email: message.recipient, contactPixelTrackingConsent: false }],
          subject: message.subject,
          textContent: message.text,
          htmlContent: message.html,
          headers: {
            idempotencyKey: message.idempotencyKey,
            'X-Correlation-Id': message.correlationId,
          },
        }),
        signal: controller.signal,
      });
    } catch {
      throw new UnknownProviderOutcomeError('Provider outcome is unknown');
    } finally {
      clearTimeout(timeout);
    }
    if (!result.ok) {
      const message = `BREVO_${String(result.status)}`;
      if (result.status === 408 || result.status === 429 || result.status >= 500)
        throw new TransientProviderRejectionError(message);
      throw new PermanentProviderRejectionError(message);
    }
    const body = (await result.json()) as { messageId?: string };
    if (!body.messageId)
      throw new UnknownProviderOutcomeError('Provider response omitted messageId');
    return body.messageId;
  }
}
