import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { Ajv2020 } from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { describe, expect, it, vi } from 'vitest';

import { SpecialistNotificationLiveController } from '../specialist-notifications/specialist-notification-live.controller.js';
import { toNotificationCreatedEvent } from '../specialist-notifications/specialist-notification-live.types.js';

describe('specialist live creation contract and owner read', () => {
  it('maps immutable persisted creation fields to the strict creation schema without content', async () => {
    const schema = JSON.parse(
      await readFile(
        new URL(
          '../../../contracts/events/content/notification-created-v1.schema.json',
          import.meta.url,
        ),
        'utf8',
      ),
    );
    const ajv = new Ajv2020({ allErrors: true });
    addFormats(ajv);
    const validate = ajv.compile(schema);
    const row = {
      id: randomUUID(),
      notification_id: randomUUID(),
      recipient_account_id: randomUUID(),
      aggregate_version: '0',
      correlation_id: randomUUID(),
      causation_id: randomUUID(),
      occurred_at: new Date('2026-11-07T01:00:00.000Z'),
      expires_at: new Date('2026-12-07T01:00:00.000Z'),
      claim_token: randomUUID(),
      attempt_count: 2,
    };
    const event = toNotificationCreatedEvent(row);
    expect(validate(event), JSON.stringify(validate.errors)).toBe(true);
    expect(event).toMatchObject({
      messageId: row.id,
      correlationId: row.correlation_id,
      causationId: row.causation_id,
      aggregateId: row.notification_id,
      aggregateVersion: 0,
      occurredAt: row.occurred_at.toISOString(),
      payload: {
        notificationId: row.notification_id,
        recipientAccountId: row.recipient_account_id,
        recipientRole: 'SPECIALIST',
        expiresAt: row.expires_at.toISOString(),
      },
    });
    expect(validate({ ...event, payload: { ...event.payload, title: 'private copy' } })).toBe(
      false,
    );
    expect(validate({ ...event, bearer: 'must-never-enter-event' })).toBe(false);
    expect(JSON.stringify(event)).not.toMatch(
      /title|body|appointmentId|claim_token|attempt_count|bearer/,
    );
    expect(
      toNotificationCreatedEvent({ ...row, claim_token: randomUUID(), attempt_count: 5 }),
    ).toEqual(event);
  });

  it('queries only the authenticated owner and returns the strict pointer', async () => {
    const notificationId = randomUUID();
    const accountId = randomUUID();
    const eligible = vi.fn().mockResolvedValue(notificationId);
    const controller = new SpecialistNotificationLiveController({ eligible } as never);
    await expect(
      controller.eligible(notificationId, { accountId, roles: ['SPECIALIST'] }),
    ).resolves.toEqual({ notificationId });
    expect(eligible).toHaveBeenCalledWith(notificationId, accountId);
  });

  it('uses the same not-found outcome for every ineligible row and fails closed on storage failure', async () => {
    const eligible = vi.fn().mockResolvedValue(null);
    const controller = new SpecialistNotificationLiveController({ eligible } as never);
    const user = { accountId: randomUUID(), roles: ['SPECIALIST'] };
    await expect(controller.eligible(randomUUID(), user)).rejects.toMatchObject({
      status: 404,
      response: { code: 'NOTIFICATION_NOT_FOUND' },
    });
    eligible.mockRejectedValueOnce(new Error('synthetic private storage detail'));
    await expect(controller.eligible(randomUUID(), user)).rejects.toMatchObject({ status: 503 });
    await expect(controller.eligible('invalid-id', user)).rejects.toMatchObject({ status: 422 });
    expect(eligible).toHaveBeenCalledTimes(2);
  });
});
