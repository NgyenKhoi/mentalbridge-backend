import { describe, expect, it, vi } from 'vitest';

import {
  AppointmentStatusConsumer,
  AppointmentStatusProjector,
} from '../appointment-reminders/appointment-status.consumer.js';

const event = {
  messageId: '25000000-0000-4000-8000-000000000001',
  messageType: 'consultation.appointment.status-changed',
  occurredAt: '2029-01-01T00:00:00.000Z',
  producer: 'consultation-service',
  schemaVersion: '2.0',
  correlationId: '25000000-0000-4000-8000-000000000002',
  aggregateId: '25000000-0000-4000-8000-000000000003',
  aggregateVersion: 1,
  payload: {
    appointmentId: '25000000-0000-4000-8000-000000000003',
    ownerAccountId: '25000000-0000-4000-8000-000000000004',
    specialistAccountId: '25000000-0000-4000-8000-000000000005',
    status: 'CONFIRMED',
    scheduledStartAt: '2030-01-02T03:00:00.000Z',
    modality: 'IN_APP_CHAT',
    replacesAppointmentId: null,
  },
};

function harness() {
  const apply = vi.fn().mockResolvedValue(undefined);
  const materialize = vi.fn().mockResolvedValue(undefined);
  const projector = new AppointmentStatusProjector(
    { apply } as never,
    { now: () => new Date('2029-01-01T00:00:00.000Z') } as never,
    { materialize } as never,
  );
  const consumer = new AppointmentStatusConsumer(
    { CONTENT_APPOINTMENT_CONSUMER_ENABLED: false } as never,
    projector,
  );
  return { apply, materialize, consumer };
}

describe('appointment status consumer', () => {
  it('projects a valid v2 event to reminder and specialist notification state', async () => {
    const { apply, materialize, consumer } = harness();
    await consumer.handle(Buffer.from(JSON.stringify(event)));
    expect(apply).toHaveBeenCalledOnce();
    expect(apply).toHaveBeenCalledWith(event, new Date('2029-01-01T00:00:00.000Z'));
    expect(materialize).toHaveBeenCalledWith(event);
  });

  it('accepts historical v1 replay for reminders without creating a specialist notification', async () => {
    const { specialistAccountId: _specialistAccountId, ...historicalPayload } = event.payload;
    const historical = { ...event, schemaVersion: '1.0', payload: historicalPayload };
    const { apply, materialize, consumer } = harness();

    await consumer.handle(Buffer.from(JSON.stringify(historical)));

    expect(apply).toHaveBeenCalledWith(historical, new Date('2029-01-01T00:00:00.000Z'));
    expect(materialize).not.toHaveBeenCalled();
  });

  it('discards malformed and mismatched events without changing reminder state', async () => {
    const { apply, consumer } = harness();
    await consumer.handle(Buffer.from('{'));
    await consumer.handle(Buffer.from(JSON.stringify({ ...event, schemaVersion: '3.0' })));
    await consumer.handle(
      Buffer.from(
        JSON.stringify({
          ...event,
          schemaVersion: '1.0',
          payload: event.payload,
        }),
      ),
    );
    await consumer.handle(Buffer.from(JSON.stringify({ ...event, aggregateId: event.messageId })));
    expect(apply).not.toHaveBeenCalled();
  });

  it('propagates a temporary persistence failure so the event is not acknowledged', async () => {
    const { apply, consumer } = harness();
    apply.mockRejectedValueOnce(new Error('database unavailable'));
    await expect(consumer.handle(Buffer.from(JSON.stringify(event)))).rejects.toThrow(
      'database unavailable',
    );
  });
});
