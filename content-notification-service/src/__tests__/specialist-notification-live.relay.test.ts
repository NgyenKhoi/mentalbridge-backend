import { Logger } from '@nestjs/common';
import { Kafka, type Producer } from 'kafkajs';
import { randomUUID } from 'node:crypto';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { SpecialistNotificationLiveRepository } from '../specialist-notifications/specialist-notification-live.repository.js';
import { SpecialistNotificationLiveRelay } from '../specialist-notifications/specialist-notification-live.relay.js';
import {
  LIVE_NOTIFICATION_TOPIC,
  toNotificationCreatedEvent,
  type SpecialistNotificationLiveClaim,
} from '../specialist-notifications/specialist-notification-live.types.js';

describe('specialist live relay bounded publication', () => {
  const configuration = {
    CONTENT_SPECIALIST_LIVE_RELAY_ENABLED: true,
    KAFKA_BOOTSTRAP_SERVERS: 'synthetic-broker:9092',
  } as ServiceConfiguration;
  let relay: SpecialistNotificationLiveRelay;
  let repository: {
    claim: ReturnType<typeof vi.fn>;
    eligibleClaim: ReturnType<typeof vi.fn>;
    published: ReturnType<typeof vi.fn>;
    suppress: ReturnType<typeof vi.fn>;
    retry: ReturnType<typeof vi.fn>;
  };
  let producer: {
    connect: ReturnType<typeof vi.fn>;
    send: ReturnType<typeof vi.fn>;
    disconnect: ReturnType<typeof vi.fn>;
  };

  function claim(): SpecialistNotificationLiveClaim {
    return {
      id: randomUUID(),
      notification_id: randomUUID(),
      recipient_account_id: randomUUID(),
      aggregate_version: '0',
      correlation_id: randomUUID(),
      causation_id: randomUUID(),
      occurred_at: new Date(),
      expires_at: new Date(Date.now() + 86_400_000),
      claim_token: randomUUID(),
      attempt_count: 1,
    };
  }

  beforeEach(() => {
    vi.spyOn(Logger.prototype, 'log').mockImplementation(() => undefined);
    vi.spyOn(Logger.prototype, 'warn').mockImplementation(() => undefined);
    repository = {
      claim: vi.fn().mockResolvedValue(null),
      eligibleClaim: vi.fn().mockResolvedValue(true),
      published: vi.fn().mockResolvedValue(true),
      suppress: vi.fn().mockResolvedValue(true),
      retry: vi.fn().mockResolvedValue(true),
    };
    producer = {
      connect: vi.fn().mockResolvedValue(undefined),
      send: vi.fn().mockResolvedValue([]),
      disconnect: vi.fn().mockResolvedValue(undefined),
    };
    vi.spyOn(Kafka.prototype, 'producer').mockReturnValue(producer as unknown as Producer);
    relay = new SpecialistNotificationLiveRelay(
      configuration,
      repository as unknown as SpecialistNotificationLiveRepository,
    );
  });

  afterEach(async () => {
    await relay.onApplicationShutdown();
    vi.restoreAllMocks();
  });

  it('does not claim or connect when relay delivery is disabled', async () => {
    const disabled = new SpecialistNotificationLiveRelay(
      { ...configuration, CONTENT_SPECIALIST_LIVE_RELAY_ENABLED: false },
      repository as unknown as SpecialistNotificationLiveRepository,
    );
    disabled.onModuleInit();
    await disabled.publishDue();
    expect(repository.claim).not.toHaveBeenCalled();
    expect(producer.connect).not.toHaveBeenCalled();
    await disabled.onApplicationShutdown();
  });

  it('suppresses an ineligible claim without Kafka work', async () => {
    const row = claim();
    repository.claim.mockResolvedValueOnce(row);
    repository.eligibleClaim.mockResolvedValueOnce(false);
    await relay.publishDue();
    expect(repository.suppress).toHaveBeenCalledWith(row);
    expect(producer.connect).not.toHaveBeenCalled();
    expect(repository.published).not.toHaveBeenCalled();
    expect(relay.metrics.suppressed).toBe(1);
  });

  it.each(['preference disabled', 'claim lease expired'])(
    'rechecks eligibility after connecting before sending when %s',
    async () => {
      const row = claim();
      repository.claim.mockResolvedValueOnce(row);
      repository.eligibleClaim.mockResolvedValueOnce(true).mockResolvedValueOnce(false);
      await relay.publishDue();
      expect(producer.connect).toHaveBeenCalledOnce();
      expect(repository.eligibleClaim).toHaveBeenCalledTimes(2);
      expect(repository.suppress).toHaveBeenCalledWith(row);
      expect(producer.send).not.toHaveBeenCalled();
    },
  );

  it('uses an idempotent producer and records PUBLISHED only after the Kafka ACK', async () => {
    const row = claim();
    repository.claim.mockResolvedValueOnce(row);
    let acknowledge!: () => void;
    producer.send.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          acknowledge = resolve;
        }),
    );
    const publishing = relay.publishDue();
    await vi.waitFor(() => expect(producer.send).toHaveBeenCalledOnce());
    expect(Kafka.prototype.producer).toHaveBeenCalledWith(
      expect.objectContaining({
        idempotent: true,
        maxInFlightRequests: 1,
        allowAutoTopicCreation: false,
      }),
    );
    expect(producer.send).toHaveBeenCalledWith({
      topic: LIVE_NOTIFICATION_TOPIC,
      acks: -1,
      timeout: 2_000,
      messages: [
        { key: row.notification_id, value: JSON.stringify(toNotificationCreatedEvent(row)) },
      ],
    });
    expect(repository.published).not.toHaveBeenCalled();
    acknowledge();
    await publishing;
    expect(repository.published).toHaveBeenCalledWith(row);
    expect(relay.metrics.acknowledged).toBe(1);
  });

  it('records lost claim fencing without claiming a successful outbox transition', async () => {
    repository.claim.mockResolvedValueOnce(claim());
    repository.published.mockResolvedValueOnce(false);
    await relay.publishDue();
    expect(relay.metrics.acknowledged).toBe(0);
    expect(relay.metrics.fenced).toBe(1);
    expect(repository.retry).not.toHaveBeenCalled();
  });

  it.each(['connect', 'send'] as const)(
    'retains a failed %s attempt for bounded retry without logging private errors',
    async (operation) => {
      const row = claim();
      repository.claim.mockResolvedValueOnce(row);
      producer[operation].mockRejectedValueOnce(new Error('private-error-body-and-token'));
      await relay.publishDue();
      expect(repository.retry).toHaveBeenCalledWith(row);
      expect(repository.published).not.toHaveBeenCalled();
      expect(producer.disconnect).toHaveBeenCalledOnce();
      expect(relay.metrics.failures).toBe(1);
      expect(JSON.stringify(vi.mocked(Logger.prototype.warn).mock.calls)).not.toContain(
        'private-error-body-and-token',
      );
    },
  );

  it('fails closed on owner storage uncertainty instead of publishing', async () => {
    const row = claim();
    repository.claim.mockResolvedValueOnce(row);
    repository.eligibleClaim.mockRejectedValueOnce(new Error('storage unavailable'));
    await relay.publishDue();
    expect(repository.retry).toHaveBeenCalledWith(row);
    expect(producer.send).not.toHaveBeenCalled();
    expect(repository.published).not.toHaveBeenCalled();
  });

  it('bounds a run to twenty claims and coalesces concurrent timer ticks', async () => {
    repository.claim.mockResolvedValue(claim());
    let connected!: () => void;
    producer.connect.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          connected = resolve;
        }),
    );
    const first = relay.publishDue(100);
    await vi.waitFor(() => expect(producer.connect).toHaveBeenCalledOnce());
    const second = relay.publishDue(100);
    expect(repository.claim).toHaveBeenCalledOnce();
    connected();
    await Promise.all([first, second]);
    expect(repository.claim).toHaveBeenCalledTimes(20);
    expect(producer.send).toHaveBeenCalledTimes(20);
    await relay.onApplicationShutdown();
    await relay.publishDue();
    expect(repository.claim).toHaveBeenCalledTimes(20);
  });
});
