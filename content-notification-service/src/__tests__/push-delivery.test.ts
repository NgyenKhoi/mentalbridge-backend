import { randomBytes } from 'node:crypto';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import {
  ExpoPushProvider,
  InvalidPushTokenError,
  TransientPushProviderError,
} from '../push-delivery/expo-push.provider.js';
import { PushDeviceService } from '../push-delivery/push-device.service.js';
import { PushDeliveryWorker } from '../push-delivery/push-delivery.worker.js';
import { PushTokenCrypto } from '../push-delivery/push-token.crypto.js';

const encryptionKey = randomBytes(32).toString('base64');
const configuration = {
  CONTENT_PUSH_TOKEN_ENCRYPTION_KEY: encryptionKey,
  CONTENT_PUSH_PROVIDER_TIMEOUT_MS: 500,
  EXPO_PUSH_BASE_URL: 'https://exp.host',
  EXPO_ACCESS_TOKEN: 'synthetic-test-token',
} as ServiceConfiguration;

afterEach(() => vi.unstubAllGlobals());

describe('native push delivery', () => {
  it('encrypts provider tokens with randomized authenticated encryption', () => {
    const crypto = new PushTokenCrypto(encryptionKey);
    const first = crypto.encrypt('ExponentPushToken[test-device-token]');
    const second = crypto.encrypt('ExponentPushToken[test-device-token]');

    expect(first.ciphertext).not.toBe('ExponentPushToken[test-device-token]');
    expect(first.ciphertext).not.toBe(second.ciphertext);
    expect(first.hash).toBe(second.hash);
    expect(crypto.decrypt(first.ciphertext, first.iv, first.authTag)).toBe(
      'ExponentPushToken[test-device-token]',
    );
  });

  it('registers against the authenticated owner and never returns the provider token', async () => {
    const register = vi.fn(async (_ownerId, input, encrypted) => ({
      installationId: input.installationId,
      platform: input.platform,
      provider: input.provider,
      permissionStatus: input.permissionStatus,
      status: 'ACTIVE' as const,
      appVersion: input.appVersion ?? null,
      lastRegisteredAt: '2026-10-22T00:00:00.000Z',
      version: 0,
      encrypted,
    }));
    const service = new PushDeviceService(
      { register, consumeCommandRateLimit: vi.fn(async () => true) } as never,
      configuration,
    );
    const result = await service.register(
      '10000000-0000-4000-8000-000000000001',
      '20000000-0000-4000-8000-000000000002',
      {
        platform: 'ANDROID',
        provider: 'EXPO',
        providerToken: 'ExponentPushToken[test-device-token]',
        permissionStatus: 'GRANTED',
        appVersion: '1.0.0',
      },
    );

    expect(register).toHaveBeenCalledWith(
      '10000000-0000-4000-8000-000000000001',
      expect.objectContaining({
        installationId: '20000000-0000-4000-8000-000000000002',
      }),
      expect.objectContaining({ hash: expect.stringMatching(/^[0-9a-f]{64}$/) }),
    );
    expect(result).not.toHaveProperty('providerToken');
  });

  it('sends only generic copy and safe routing pointers to Expo', async () => {
    const fetchMock = vi.fn(async (_url, init?: RequestInit) => {
      const payload = JSON.parse(String(init?.body)) as Record<string, unknown>;
      expect(payload.title).toBe('MentalBridge');
      expect(payload.body).toBe('Bạn có một cập nhật mới trong MentalBridge.');
      expect(JSON.stringify(payload)).not.toMatch(/journal|assessment|crisis|chatBody|AI/i);
      return new Response(JSON.stringify({ data: { status: 'ok', id: 'ticket-1' } }), {
        status: 200,
      });
    });
    vi.stubGlobal('fetch', fetchMock);
    const provider = new ExpoPushProvider(configuration);

    await expect(
      provider.send({
        token: 'ExponentPushToken[test-device-token]',
        title: 'MentalBridge',
        notificationId: '30000000-0000-4000-8000-000000000003',
        actionType: 'OPEN_RESOURCES',
        actionTargetId: null,
      }),
    ).resolves.toBe('ticket-1');
  });

  it('classifies invalid tokens and retryable provider failures', async () => {
    const provider = new ExpoPushProvider(configuration);
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(
            JSON.stringify({
              data: { status: 'error', details: { error: 'DeviceNotRegistered' } },
            }),
            { status: 200 },
          ),
      ),
    );
    await expect(
      provider.send({
        token: 'ExponentPushToken[invalid-device]',
        title: 'MentalBridge',
        notificationId: '30000000-0000-4000-8000-000000000003',
        actionType: null,
        actionTargetId: null,
      }),
    ).rejects.toBeInstanceOf(InvalidPushTokenError);

    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('', { status: 429 })),
    );
    await expect(
      provider.send({
        token: 'ExponentPushToken[test-device-token]',
        title: 'MentalBridge',
        notificationId: '30000000-0000-4000-8000-000000000003',
        actionType: null,
        actionTargetId: null,
      }),
    ).rejects.toBeInstanceOf(TransientPushProviderError);
  });

  it('maps Expo receipts without treating ticket acceptance as final delivery', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(
            JSON.stringify({
              data: {
                'ticket-ok': { status: 'ok' },
                'ticket-invalid': {
                  status: 'error',
                  details: { error: 'DeviceNotRegistered' },
                },
              },
            }),
            { status: 200 },
          ),
      ),
    );
    const receipts = await new ExpoPushProvider(configuration).getReceipts([
      'ticket-ok',
      'ticket-invalid',
      'ticket-pending',
    ]);

    expect(receipts.get('ticket-ok')).toEqual({ status: 'CONFIRMED' });
    expect(receipts.get('ticket-invalid')).toEqual({
      status: 'ERROR',
      code: 'DEVICE_NOT_REGISTERED',
    });
    expect(receipts.get('ticket-pending')).toEqual({ status: 'PENDING' });
  });

  it('prunes an invalid device without retrying or claiming delivery', async () => {
    const encrypted = new PushTokenCrypto(encryptionKey).encrypt(
      'ExponentPushToken[invalid-device]',
    );
    const repository = {
      materializeCandidates: vi.fn(async () => 1),
      settleIneligible: vi.fn(async () => undefined),
      claim: vi.fn(async () => [
        {
          id: 'attempt-1',
          deviceId: 'device-1',
          tokenCiphertext: encrypted.ciphertext,
          tokenIv: encrypted.iv,
          tokenAuthTag: encrypted.authTag,
          title: 'MentalBridge',
          notificationId: '30000000-0000-4000-8000-000000000003',
          actionType: null,
          actionTargetId: null,
          attemptCount: 1,
        },
      ]),
      isDeliverable: vi.fn(async () => true),
      suppressClaim: vi.fn(),
      accepted: vi.fn(),
      failed: vi.fn(async () => undefined),
      unknown: vi.fn(),
      invalidateDevice: vi.fn(async () => undefined),
      claimReceipts: vi.fn(async () => []),
    };
    const provider = {
      send: vi.fn(async () => Promise.reject(new InvalidPushTokenError())),
      getReceipts: vi.fn(),
    };
    const worker = new PushDeliveryWorker(
      {
        ...configuration,
        CONTENT_PUSH_DELIVERY_ENABLED: true,
        CONTENT_PUSH_DELIVERY_BATCH_SIZE: 10,
        CONTENT_PUSH_DELIVERY_INTERVAL_MS: 60_000,
        CONTENT_PUSH_RECEIPT_INITIAL_DELAY_SECONDS: 900,
        CONTENT_PUSH_RECEIPT_RETRY_DELAY_SECONDS: 300,
      } as ServiceConfiguration,
      repository as never,
      provider,
    );

    await worker.runOnce();

    expect(repository.invalidateDevice).toHaveBeenCalledWith('device-1');
    expect(repository.failed).toHaveBeenCalledWith('attempt-1', 'DEVICE_NOT_REGISTERED', false);
    expect(repository.accepted).not.toHaveBeenCalled();
  });

  it('invalidates a device when Expo reports DeviceNotRegistered in a receipt', async () => {
    const repository = {
      materializeCandidates: vi.fn(async () => 0),
      settleIneligible: vi.fn(),
      claim: vi.fn(async () => []),
      claimReceipts: vi.fn(async () => [
        {
          id: 'attempt-1',
          deviceId: 'device-1',
          providerTicketId: 'ticket-1',
          receiptAttemptCount: 1,
        },
      ]),
      confirmReceipt: vi.fn(),
      failReceipt: vi.fn(),
      retryReceipt: vi.fn(),
      invalidateDevice: vi.fn(),
    };
    const provider = {
      send: vi.fn(),
      getReceipts: vi.fn(
        async () =>
          new Map([['ticket-1', { status: 'ERROR' as const, code: 'DEVICE_NOT_REGISTERED' }]]),
      ),
    };
    const worker = new PushDeliveryWorker(
      {
        ...configuration,
        CONTENT_PUSH_DELIVERY_ENABLED: true,
        CONTENT_PUSH_DELIVERY_BATCH_SIZE: 10,
        CONTENT_PUSH_DELIVERY_INTERVAL_MS: 60_000,
        CONTENT_PUSH_RECEIPT_INITIAL_DELAY_SECONDS: 900,
        CONTENT_PUSH_RECEIPT_RETRY_DELAY_SECONDS: 300,
      } as ServiceConfiguration,
      repository as never,
      provider,
    );

    await worker.runOnce();

    expect(repository.invalidateDevice).toHaveBeenCalledWith('device-1');
    expect(repository.failReceipt).toHaveBeenCalledWith('attempt-1', 'DEVICE_NOT_REGISTERED');
    expect(repository.confirmReceipt).not.toHaveBeenCalled();
  });

  it('revalidates a claimed notification before calling the provider', async () => {
    const repository = {
      materializeCandidates: vi.fn(async () => 0),
      settleIneligible: vi.fn(),
      claim: vi.fn(async () => [
        {
          id: 'attempt-1',
          deviceId: 'device-1',
          tokenCiphertext: 'ciphertext',
          tokenIv: 'iv',
          tokenAuthTag: 'tag',
          title: 'MentalBridge',
          notificationId: 'notification-1',
          actionType: null,
          actionTargetId: null,
          attemptCount: 1,
        },
      ]),
      isDeliverable: vi.fn(async () => false),
      suppressClaim: vi.fn(),
      claimReceipts: vi.fn(async () => []),
    };
    const provider = { send: vi.fn(), getReceipts: vi.fn() };
    const worker = new PushDeliveryWorker(
      {
        ...configuration,
        CONTENT_PUSH_DELIVERY_ENABLED: true,
        CONTENT_PUSH_DELIVERY_BATCH_SIZE: 10,
        CONTENT_PUSH_DELIVERY_INTERVAL_MS: 60_000,
        CONTENT_PUSH_RECEIPT_INITIAL_DELAY_SECONDS: 900,
        CONTENT_PUSH_RECEIPT_RETRY_DELAY_SECONDS: 300,
      } as ServiceConfiguration,
      repository as never,
      provider,
    );

    await worker.runOnce();

    expect(repository.suppressClaim).toHaveBeenCalledWith('attempt-1', 'NOTIFICATION_UNAVAILABLE');
    expect(provider.send).not.toHaveBeenCalled();
  });
});
