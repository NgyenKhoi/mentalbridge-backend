import { describe, expect, it, vi } from 'vitest';

import { AppointmentPushReminderService } from '../appointment-reminders/appointment-push-reminder.service.js';
import type { ClaimedAppointmentPushReminder } from '../appointment-reminders/appointment-reminder.types.js';
import type { NotificationPreferences } from '../notification-preferences/notification-preference.types.js';

const NOW = new Date('2026-10-24T01:00:00.000Z');

const reminder = (
  overrides: Partial<ClaimedAppointmentPushReminder> = {},
): ClaimedAppointmentPushReminder => ({
  id: '10000000-0000-4000-8000-000000000001',
  recipientId: '10000000-0000-4000-8000-000000000002',
  appointmentId: '10000000-0000-4000-8000-000000000003',
  appointmentVersion: 4,
  modality: 'IN_APP_VIDEO',
  scheduledStartAt: new Date('2026-10-24T03:00:00.000Z'),
  targetAt: new Date('2026-10-24T02:00:00.000Z'),
  dueAt: NOW,
  attemptCount: 1,
  firstAttemptAt: NOW,
  ...overrides,
});

const preference = (overrides: Partial<NotificationPreferences> = {}): NotificationPreferences => ({
  notificationsEnabled: true,
  channels: { inApp: true, email: false, push: true },
  contentGroups: {
    journalReminder: true,
    emotionCheckIn: true,
    streakMilestone: true,
    screeningReassessment: true,
    appointmentMessage: true,
    resourceSystem: true,
    communityInteraction: true,
  },
  quietHours: { enabled: false, start: '22:00', end: '07:00', timeZone: 'Asia/Ho_Chi_Minh' },
  email: {
    cadence: 'DAILY_DIGEST',
    wellbeingDigestEnabled: false,
    resourceRemindersEnabled: false,
    appointmentRemindersEnabled: false,
  },
  version: 1,
  updatedAt: NOW.toISOString(),
  ...overrides,
});

function harness(
  options: {
    item?: ClaimedAppointmentPushReminder;
    preferences?: NotificationPreferences;
    truthEligible?: boolean;
    truthVersion?: number;
    truthError?: boolean;
    clock?: () => Date;
  } = {},
) {
  const item = options.item ?? reminder();
  const repository = {
    claimPushDue: vi.fn(async () => [item]),
    materializePush: vi.fn(async () => true),
    retryPush: vi.fn(async () => undefined),
    finishPush: vi.fn(async () => undefined),
  };
  const appointments = {
    get: vi.fn(async () => {
      if (options.truthError) throw new Error('unavailable');
      return {
        appointmentId: item.appointmentId,
        ownerAccountId: item.recipientId,
        version: options.truthVersion ?? item.appointmentVersion,
        status: 'CONFIRMED',
        scheduledStartAt: item.scheduledStartAt.toISOString(),
        modality: item.modality,
        eligible: options.truthEligible ?? true,
      };
    }),
  };
  const service = new AppointmentPushReminderService(
    { CONTENT_PUSH_DELIVERY_ENABLED: true } as never,
    repository as never,
    { get: vi.fn(async () => options.preferences ?? preference()) } as never,
    appointments,
    { now: options.clock ?? (() => NOW) },
  );
  return { service, repository, appointments, item };
}

describe('appointment native push reminder', () => {
  it('materializes one minimized durable fact after exact owner reauthorization', async () => {
    const { service, repository, appointments } = harness();

    await expect(service.runDue(20)).resolves.toEqual({ materialized: 1, failed: 0 });

    expect(appointments.get).toHaveBeenCalledWith('10000000-0000-4000-8000-000000000003', 4);
    expect(repository.materializePush).toHaveBeenCalledOnce();
  });

  it('terminally suppresses a disabled push or appointment-message preference', async () => {
    const disabled = preference({ channels: { inApp: true, email: false, push: false } });
    const { service, repository, appointments } = harness({ preferences: disabled });

    await service.runDue(20);

    expect(repository.finishPush).toHaveBeenCalledWith(
      expect.any(String),
      'SUPPRESSED',
      NOW,
      'PREFERENCE_DISABLED',
    );
    expect(appointments.get).not.toHaveBeenCalled();
  });

  it('defers through quiet hours only when their end remains before appointment start', async () => {
    const quiet = preference({
      quietHours: { enabled: true, start: '07:00', end: '09:00', timeZone: 'Asia/Ho_Chi_Minh' },
    });
    const { service, repository } = harness({ preferences: quiet });

    await service.runDue(20);

    expect(repository.retryPush).toHaveBeenCalledWith(
      expect.any(String),
      new Date('2026-10-24T02:00:00.000Z'),
      'QUIET_HOURS_DEFERRED',
      NOW,
    );
    expect(repository.materializePush).not.toHaveBeenCalled();
  });

  it('suppresses instead of overriding quiet hours when the appointment starts first', async () => {
    const quiet = preference({
      quietHours: { enabled: true, start: '07:00', end: '09:00', timeZone: 'Asia/Ho_Chi_Minh' },
    });
    const { service, repository } = harness({
      item: reminder({ scheduledStartAt: new Date('2026-10-24T01:30:00.000Z') }),
      preferences: quiet,
    });

    await service.runDue(20);

    expect(repository.finishPush).toHaveBeenCalledWith(
      expect.any(String),
      'SUPPRESSED',
      NOW,
      'QUIET_HOURS_OVERLAP_START',
    );
  });

  it.each([
    ['cancelled', false, 4],
    ['rescheduled', true, 5],
  ])(
    'invalidates %s owner truth without creating an inbox fact',
    async (_name, eligible, version) => {
      const { service, repository } = harness({ truthEligible: eligible, truthVersion: version });

      await service.runDue(20);

      expect(repository.finishPush).toHaveBeenCalledWith(
        expect.any(String),
        'INVALIDATED',
        NOW,
        'APPOINTMENT_NOT_ELIGIBLE',
      );
      expect(repository.materializePush).not.toHaveBeenCalled();
    },
  );

  it('fails closed and retries a temporary Consultation outage', async () => {
    const { service, repository } = harness({ truthError: true });

    await service.runDue(20);

    expect(repository.retryPush).toHaveBeenCalledWith(
      expect.any(String),
      new Date('2026-10-24T01:01:00.000Z'),
      'CONSULTATION_UNAVAILABLE',
      NOW,
    );
    expect(repository.materializePush).not.toHaveBeenCalled();
  });

  it('reauthorizes an already materialized claim immediately before provider submission', async () => {
    const { service, item } = harness({ truthEligible: false });
    const decision = await service.authorizeClaim({
      id: 'attempt-1',
      deviceId: 'device-1',
      tokenCiphertext: 'ciphertext',
      tokenIv: 'iv',
      tokenAuthTag: 'tag',
      title: 'Sắp đến giờ hẹn của bạn',
      notificationId: '20000000-0000-4000-8000-000000000001',
      actionType: 'OPEN_APPOINTMENT',
      actionTargetId: item.appointmentId,
      attemptCount: 1,
      appointmentReminder: {
        recipientId: item.recipientId,
        appointmentId: item.appointmentId,
        appointmentVersion: item.appointmentVersion,
        modality: item.modality,
        scheduledStartAt: item.scheduledStartAt,
      },
    });

    expect(decision).toEqual({ outcome: 'SUPPRESS', code: 'APPOINTMENT_NOT_ELIGIBLE' });
  });
});
