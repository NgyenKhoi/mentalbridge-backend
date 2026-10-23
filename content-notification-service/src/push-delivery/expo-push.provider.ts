import { Inject, Injectable } from '@nestjs/common';

import { CONFIGURATION_TOKEN } from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';

export class InvalidPushTokenError extends Error {}
export class TransientPushProviderError extends Error {}
export class UnknownPushProviderOutcomeError extends Error {}

export interface PushProviderMessage {
  readonly token: string;
  readonly title: string;
  readonly notificationId: string;
  readonly actionType: string | null;
  readonly actionTargetId: string | null;
}

export interface PushProvider {
  send(message: PushProviderMessage): Promise<string>;
}

@Injectable()
export class ExpoPushProvider implements PushProvider {
  constructor(@Inject(CONFIGURATION_TOKEN) private readonly config: ServiceConfiguration) {}

  async send(message: PushProviderMessage): Promise<string> {
    const controller = new AbortController();
    const timeout = setTimeout(() => {
      controller.abort();
    }, this.config.CONTENT_PUSH_PROVIDER_TIMEOUT_MS);
    let response: Response;
    try {
      response = await fetch(new URL('/--/api/v2/push/send', this.config.EXPO_PUSH_BASE_URL), {
        method: 'POST',
        headers: {
          authorization: `Bearer ${this.config.EXPO_ACCESS_TOKEN ?? ''}`,
          'content-type': 'application/json',
          accept: 'application/json',
        },
        body: JSON.stringify({
          to: message.token,
          title: 'MentalBridge',
          body: 'Bạn có một cập nhật mới trong MentalBridge.',
          data: {
            notificationId: message.notificationId,
            actionType: message.actionType,
            actionTargetId: message.actionTargetId,
          },
          sound: 'default',
        }),
        signal: controller.signal,
      });
    } catch {
      throw new UnknownPushProviderOutcomeError();
    } finally {
      clearTimeout(timeout);
    }
    if (response.status === 408 || response.status === 429 || response.status >= 500)
      throw new TransientPushProviderError(`EXPO_${String(response.status)}`);
    if (!response.ok) throw new Error(`EXPO_${String(response.status)}`);
    const payload = (await response.json()) as {
      data?: { status?: string; id?: string; details?: { error?: string } };
    };
    if (payload.data?.details?.error === 'DeviceNotRegistered') throw new InvalidPushTokenError();
    if (payload.data?.status !== 'ok' || !payload.data.id) throw new Error('EXPO_REJECTED');
    return payload.data.id;
  }
}
