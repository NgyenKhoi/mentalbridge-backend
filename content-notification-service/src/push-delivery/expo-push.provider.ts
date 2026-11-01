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
  getReceipts(ticketIds: readonly string[]): Promise<ReadonlyMap<string, PushReceiptResult>>;
}

export type PushReceiptResult =
  | { readonly status: 'CONFIRMED' }
  | { readonly status: 'ERROR'; readonly code: string }
  | { readonly status: 'PENDING' };

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

  async getReceipts(ticketIds: readonly string[]): Promise<ReadonlyMap<string, PushReceiptResult>> {
    if (ticketIds.length === 0) return new Map();
    const controller = new AbortController();
    const timeout = setTimeout(() => {
      controller.abort();
    }, this.config.CONTENT_PUSH_PROVIDER_TIMEOUT_MS);
    let response: Response;
    try {
      response = await fetch(
        new URL('/--/api/v2/push/getReceipts', this.config.EXPO_PUSH_BASE_URL),
        {
          method: 'POST',
          headers: {
            authorization: `Bearer ${this.config.EXPO_ACCESS_TOKEN ?? ''}`,
            'content-type': 'application/json',
            accept: 'application/json',
          },
          body: JSON.stringify({ ids: ticketIds }),
          signal: controller.signal,
        },
      );
    } catch {
      throw new TransientPushProviderError('EXPO_RECEIPT_NETWORK');
    } finally {
      clearTimeout(timeout);
    }
    if (response.status === 408 || response.status === 429 || response.status >= 500)
      throw new TransientPushProviderError(`EXPO_RECEIPT_${String(response.status)}`);
    if (!response.ok) throw new Error(`EXPO_RECEIPT_${String(response.status)}`);
    const payload = (await response.json()) as {
      data?: Record<string, { status?: string; details?: { error?: string } }>;
    };
    const results = new Map<string, PushReceiptResult>();
    for (const ticketId of ticketIds) {
      const receipt = payload.data?.[ticketId];
      if (!receipt) results.set(ticketId, { status: 'PENDING' });
      else if (receipt.status === 'ok') results.set(ticketId, { status: 'CONFIRMED' });
      else
        results.set(ticketId, {
          status: 'ERROR',
          code:
            receipt.details?.error === 'DeviceNotRegistered'
              ? 'DEVICE_NOT_REGISTERED'
              : 'PROVIDER_RECEIPT_REJECTED',
        });
    }
    return results;
  }
}
