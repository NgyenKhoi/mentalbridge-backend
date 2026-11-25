import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { describe, expect, it, vi } from 'vitest';

import { AccountLifecycleProjector } from '../notification-preferences/account-lifecycle.consumer.js';
import type { NotificationPreferenceService } from '../notification-preferences/notification-preference.service.js';
import {
  CareUserNotificationConsumer,
  CareUserNotificationProjector,
} from '../user-notifications/care-user-notification.consumer.js';
import { UserNotificationRepository } from '../user-notifications/user-notification.repository.js';
import {
  CareUserNotificationFactSchema,
  CARE_USER_NOTIFICATION_TOPIC,
  IdentityUserNotificationFactSchema,
  type CareUserNotificationFact,
  type IdentityUserNotificationFact,
  type UserNotificationDeadLetter,
  type UserNotificationDeadLetterPublisher,
} from '../user-notifications/user-notification.types.js';

const careEvent: CareUserNotificationFact = {
  messageId: '10000000-0000-4000-8000-000000000001',
  messageType: 'care.user-notification.fact',
  occurredAt: '2026-10-19T08:00:00.000Z',
  producer: 'care-service',
  schemaVersion: '1.0',
  correlationId: '10000000-0000-4000-8000-000000000002',
  aggregateId: '10000000-0000-4000-8000-000000000003',
  aggregateVersion: 0,
  payload: {
    recipientId: '10000000-0000-4000-8000-000000000004',
    factType: 'ASSESSMENT_RESULT_AVAILABLE',
    targetId: '10000000-0000-4000-8000-000000000003',
    targetVersion: 0,
    availableAt: '2026-10-19T08:00:00.000Z',
  },
};

const identityEvent: IdentityUserNotificationFact = {
  messageId: '20000000-0000-4000-8000-000000000001',
  messageType: 'identity.user-notification.fact',
  occurredAt: '2026-10-19T09:00:00.000Z',
  producer: 'identity-service',
  schemaVersion: '1.0',
  correlationId: '20000000-0000-4000-8000-000000000002',
  aggregateId: '20000000-0000-4000-8000-000000000003',
  aggregateVersion: 1,
  payload: {
    recipientId: '20000000-0000-4000-8000-000000000003',
    actorType: 'USER',
    factType: 'ACCOUNT_ACTIVATED',
    availableAt: '2026-10-19T09:00:00.000Z',
  },
};

describe('General user notification producers', () => {
  it('keeps runtime schemas aligned with strict minimized owner contracts', async () => {
    const [careContract, identityContract] = await Promise.all([
      readFile(
        fileURLToPath(
          new URL(
            '../../../contracts/events/care/user-notification-fact-v1.schema.json',
            import.meta.url,
          ),
        ),
        'utf8',
      ),
      readFile(
        fileURLToPath(
          new URL(
            '../../../contracts/events/identity/user-notification-fact-v1.schema.json',
            import.meta.url,
          ),
        ),
        'utf8',
      ),
    ]);
    expect(JSON.parse(careContract).properties.messageType.const).toBe(
      'care.user-notification.fact',
    );
    expect(JSON.parse(identityContract).properties.messageType.const).toBe(
      'identity.user-notification.fact',
    );
    expect(CareUserNotificationFactSchema.parse(careEvent)).toEqual(careEvent);
    expect(IdentityUserNotificationFactSchema.parse(identityEvent)).toEqual(identityEvent);
    expect(() =>
      CareUserNotificationFactSchema.parse({
        ...careEvent,
        payload: { ...careEvent.payload, totalScore: 12 },
      }),
    ).toThrow();
  });

  it('fails closed when a valid-looking event is bound to another target or recipient', () => {
    expect(() =>
      CareUserNotificationFactSchema.parse({
        ...careEvent,
        aggregateId: '10000000-0000-4000-8000-000000000099',
      }),
    ).toThrow();
    expect(() =>
      IdentityUserNotificationFactSchema.parse({
        ...identityEvent,
        payload: {
          ...identityEvent.payload,
          recipientId: '20000000-0000-4000-8000-000000000099',
        },
      }),
    ).toThrow();
  });

  it('projects approved generic copy and exact safe deep links without sensitive source data', async () => {
    const materialize = vi.fn().mockResolvedValue(undefined);
    const projector = new CareUserNotificationProjector({ materialize } as never);
    await projector.project(careEvent);

    expect(materialize).toHaveBeenCalledWith(
      expect.objectContaining({
        sourceIdentity: `ASSESSMENT_RESULT_AVAILABLE:${careEvent.payload.targetId}:0`,
        ownerId: careEvent.payload.recipientId,
        kind: 'ASSESSMENT_RESULT_AVAILABLE',
        preferenceGroup: 'SCREENING_REASSESSMENT',
        actionType: 'OPEN_ASSESSMENTS',
        actionTargetId: null,
      }),
      expect.stringMatching(/^[0-9a-f]{64}$/),
    );
    expect(JSON.stringify(materialize.mock.calls[0][0])).not.toMatch(
      /totalScore|screeningLevel|safetyStatus|answers|journal|provider/i,
    );

    await projector.project({
      ...careEvent,
      messageId: '10000000-0000-4000-8000-000000000099',
      correlationId: '10000000-0000-4000-8000-000000000098',
    });
    expect(materialize.mock.calls[1]).toEqual(materialize.mock.calls[0]);
  });

  it('creates system notifications only for USER activation facts', async () => {
    const materialize = vi.fn().mockResolvedValue(undefined);
    const projector = new AccountLifecycleProjector(
      { initializeForAccount: vi.fn() } as unknown as NotificationPreferenceService,
      { materialize } as never,
    );
    await projector.project(identityEvent);
    await projector.project({
      ...identityEvent,
      payload: { ...identityEvent.payload, actorType: 'SPECIALIST' },
    });

    expect(materialize).toHaveBeenCalledTimes(1);
    expect(materialize).toHaveBeenCalledWith(
      expect.objectContaining({
        sourceIdentity: `ACCOUNT_ACTIVATED:${identityEvent.aggregateId}:1`,
        kind: 'ACCOUNT_STATUS',
        preferenceGroup: 'RESOURCE_SYSTEM',
        actionType: null,
      }),
      expect.stringMatching(/^[0-9a-f]{64}$/),
    );
  });

  it('persists a cancelled dedupe record when the mapped preference group is disabled', async () => {
    const query = vi
      .fn()
      .mockResolvedValueOnce({ rows: [] })
      .mockResolvedValueOnce({
        rows: [
          {
            owner_type: 'USER',
            notifications_enabled: true,
            channel_in_app_enabled: true,
            group_screening_reassessment_enabled: false,
            group_resource_system_enabled: true,
          },
        ],
      })
      .mockResolvedValueOnce({
        rows: [
          {
            id: '30000000-0000-4000-8000-000000000001',
            request_fingerprint: 'fingerprint',
            delivery_state: 'CANCELLED',
          },
        ],
      });
    const db = {
      withTransaction: (operation: (client: { query: typeof query }) => unknown) =>
        operation({ query }),
    };
    const repository = new UserNotificationRepository(db as never);

    await repository.materialize(
      {
        sourceIdentity: `ASSESSMENT_RESULT_AVAILABLE:${careEvent.payload.targetId}:0`,
        ownerId: careEvent.payload.recipientId,
        kind: 'ASSESSMENT_RESULT_AVAILABLE',
        title: 'Kết quả sàng lọc đã sẵn sàng',
        body: 'Bạn có thể xem lại kết quả.',
        occurredAt: careEvent.occurredAt,
        preferenceGroup: 'SCREENING_REASSESSMENT',
        source: 'CARE_USER_NOTIFICATION_V1',
        actionType: 'OPEN_ASSESSMENTS',
        actionTargetId: null,
      },
      'fingerprint',
    );

    expect(query.mock.calls[2][1]?.at(-1)).toBe('CANCELLED');
  });

  it('parks malformed facts as digest-only metadata without projecting private payloads', async () => {
    const parked: UserNotificationDeadLetter[] = [];
    const deadLetters: UserNotificationDeadLetterPublisher = {
      publish: async (item) => {
        parked.push(item);
      },
    };
    const project = vi.fn();
    const consumer = new CareUserNotificationConsumer(
      { CONTENT_CARE_USER_NOTIFICATION_CONSUMER_ENABLED: false } as never,
      { project } as never,
      deadLetters,
    );

    await consumer.handle(
      CARE_USER_NOTIFICATION_TOPIC,
      1,
      '7',
      Buffer.from('{"private":"assessment answer"'),
    );
    await consumer.handle(
      CARE_USER_NOTIFICATION_TOPIC,
      1,
      '8',
      Buffer.from(JSON.stringify({ ...careEvent, schemaVersion: '2.0' })),
    );

    expect(parked.map((item) => item.errorCode)).toEqual(['INVALID_JSON', 'INVALID_EVENT']);
    expect(parked[0]).toEqual(
      expect.objectContaining({
        sourceTopic: CARE_USER_NOTIFICATION_TOPIC,
        sourcePartition: 1,
        sourceOffset: '7',
        sourceMessageDigest: expect.stringMatching(/^[0-9a-f]{64}$/),
      }),
    );
    expect(JSON.stringify(parked)).not.toContain('assessment answer');
    expect(project).not.toHaveBeenCalled();
  });

  it('retries a transient projection failure before allowing the Kafka offset to complete', async () => {
    const project = vi
      .fn()
      .mockRejectedValueOnce(new Error('database unavailable'))
      .mockResolvedValue(undefined);
    const consumer = new CareUserNotificationConsumer(
      { CONTENT_CARE_USER_NOTIFICATION_CONSUMER_ENABLED: false } as never,
      { project } as never,
      { publish: vi.fn() },
    );

    await consumer.handle(
      CARE_USER_NOTIFICATION_TOPIC,
      0,
      '9',
      Buffer.from(JSON.stringify(careEvent)),
    );

    expect(project).toHaveBeenCalledTimes(2);
  });
});
