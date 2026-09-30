import { describe, expect, it } from 'vitest';

import {
  PermanentProviderRejectionError,
  UnknownProviderOutcomeError,
} from '../appointment-reminders/appointment-email.delivery.js';
import { AppointmentReminderService } from '../appointment-reminders/appointment-reminder.service.js';
import type { ClaimedAppointmentReminder } from '../appointment-reminders/appointment-reminder.types.js';
import type { NotificationPreferences } from '../notification-preferences/notification-preference.types.js';

const now = new Date('2026-10-01T01:00:00.000Z');

const reminder = (
  overrides: Partial<ClaimedAppointmentReminder> = {},
): ClaimedAppointmentReminder => ({
  id: '10000000-0000-4000-8000-000000000001',
  recipientId: '10000000-0000-4000-8000-000000000002',
  appointmentId: '10000000-0000-4000-8000-000000000003',
  appointmentVersion: 1,
  modality: 'IN_APP_CHAT',
  scheduledStartAt: new Date('2026-10-01T02:00:00.000Z'),
  targetAt: now,
  dueAt: now,
  attemptCount: 1,
  firstAttemptAt: now,
  providerIdempotencyKey: '10000000-0000-5000-8000-000000000004',
  ...overrides,
});

const preference = (overrides: Partial<NotificationPreferences> = {}): NotificationPreferences => ({
  notificationsEnabled: true,
  channels: { inApp: true, email: true, push: false },
  contentGroups: {
    journalReminder: true,
    emotionCheckIn: true,
    streakMilestone: true,
    screeningReassessment: true,
    appointmentMessage: true,
    resourceSystem: true,
  },
  quietHours: { enabled: false, start: '22:00', end: '07:00', timeZone: 'Asia/Ho_Chi_Minh' },
  email: {
    cadence: 'DAILY_DIGEST',
    wellbeingDigestEnabled: true,
    resourceRemindersEnabled: false,
    appointmentRemindersEnabled: true,
  },
  version: 1,
  updatedAt: now.toISOString(),
  ...overrides,
});

function harness(
  item = reminder(),
  preferences = preference(),
  providerError?: Error,
  options: {
    clockNow?: () => Date;
    eligible?: boolean | (() => boolean);
    truthVersion?: number;
    onAddress?: () => void;
  } = {},
) {
  const calls: {
    finish: unknown[][];
    retry: unknown[][];
    delivered: unknown[][];
    messages: unknown[];
  } = {
    finish: [],
    retry: [],
    delivered: [],
    messages: [],
  };
  const repository = {
    claimDue: async () => [item],
    finish: async (...args: unknown[]) => void calls.finish.push(args),
    retry: async (...args: unknown[]) => void calls.retry.push(args),
    delivered: async (...args: unknown[]) => void calls.delivered.push(args),
  };
  const service = new AppointmentReminderService(
    {
      APPOINTMENT_REMINDER_ENABLED: true,
      REMINDER_SCHEDULER_BATCH_SIZE: 100,
      APPOINTMENT_REMINDER_APP_URL: 'https://app.mentalbridge.test/appointments',
    } as never,
    repository as never,
    { get: async () => preferences } as never,
    {
      get: async () => ({
        appointmentId: item.appointmentId,
        ownerAccountId: item.recipientId,
        version: options.truthVersion ?? item.appointmentVersion,
        status: 'CONFIRMED',
        scheduledStartAt: item.scheduledStartAt.toISOString(),
        modality: item.modality,
        eligible:
          typeof options.eligible === 'function' ? options.eligible() : (options.eligible ?? true),
      }),
    } as never,
    {
      get: async () => {
        options.onAddress?.();
        return 'synthetic.user@example.test';
      },
    } as never,
    {
      send: async (message: unknown) => {
        calls.messages.push(message);
        if (providerError) throw providerError;
        return 'provider-message-1';
      },
    } as never,
    { now: options.clockNow ?? (() => now) },
  );
  return { service, calls };
}

describe('appointment reminder owner behavior', () => {
  it('delivers one minimized appointment-only message independently of digest cadence', async () => {
    const { service, calls } = harness();
    await expect(service.runDue(100)).resolves.toEqual({ delivered: 1, failed: 0 });
    expect(calls.messages).toHaveLength(1);
    expect(calls.delivered).toHaveLength(1);
    const serialized = JSON.stringify(calls.messages[0]);
    expect(serialized).toContain('chat trong ứng dụng');
    expect(serialized).toContain('/appointments/10000000-0000-4000-8000-000000000003');
    expect(serialized).not.toMatch(/Journal|assessment|ConsultationBrief|chat content|safety/i);
  });

  it('suppresses a disabled dedicated preference and never calls the provider', async () => {
    const disabled = preference({
      email: { ...preference().email, appointmentRemindersEnabled: false },
    });
    const { service, calls } = harness(reminder(), disabled);
    await service.runDue(100);
    expect(calls.messages).toHaveLength(0);
    expect(calls.finish[0]?.[1]).toBe('SUPPRESSED');
  });

  it('expires without provider submission at appointment start', async () => {
    const { service, calls } = harness(reminder({ scheduledStartAt: now }));
    await service.runDue(100);
    expect(calls.messages).toHaveLength(0);
    expect(calls.finish[0]?.[1]).toBe('EXPIRED');
  });

  it('expires if dependency calls cross appointment start before provider submission', async () => {
    let checks = 0;
    const { service, calls } = harness(reminder(), preference(), undefined, {
      clockNow: () => (++checks === 1 ? now : new Date('2026-10-01T02:00:00.000Z')),
    });
    await service.runDue(100);
    expect(calls.messages).toHaveLength(0);
    expect(calls.finish[0]?.[1]).toBe('EXPIRED');
    expect(calls.finish[0]?.[3]).toBe('APPOINTMENT_STARTED');
  });

  it.each([
    ['cancelled', false, 1],
    ['rescheduled', true, 2],
  ])(
    'invalidates %s appointment truth before submission',
    async (_case, eligible, truthVersion) => {
      const { service, calls } = harness(reminder(), preference(), undefined, {
        eligible,
        truthVersion,
      });
      await service.runDue(100);
      expect(calls.messages).toHaveLength(0);
      expect(calls.finish[0]?.[1]).toBe('INVALIDATED');
    },
  );

  it('defers quiet-hour delivery only while the quiet end remains before start', async () => {
    const quiet = preference({
      quietHours: { enabled: true, start: '07:00', end: '09:00', timeZone: 'Asia/Ho_Chi_Minh' },
    });
    const { service, calls } = harness(
      reminder({ scheduledStartAt: new Date('2026-10-01T03:00:00.000Z') }),
      quiet,
    );
    await service.runDue(100);
    expect(calls.messages).toHaveLength(0);
    expect(calls.retry).toHaveLength(1);
  });

  it('suppresses when execution enters quiet hours before provider submission', async () => {
    let checks = 0;
    const quiet = preference({
      quietHours: { enabled: true, start: '08:30', end: '09:30', timeZone: 'Asia/Ho_Chi_Minh' },
    });
    const { service, calls } = harness(reminder(), quiet, undefined, {
      clockNow: () => (++checks === 1 ? now : new Date('2026-10-01T01:40:00.000Z')),
    });
    await service.runDue(100);
    expect(calls.messages).toHaveLength(0);
    expect(calls.finish[0]?.[1]).toBe('SUPPRESSED');
    expect(calls.finish[0]?.[3]).toBe('QUIET_HOURS_OVERLAP_START');
  });

  it('rechecks authoritative appointment truth after resolving the address', async () => {
    let cancelled = false;
    const { service, calls } = harness(reminder(), preference(), undefined, {
      onAddress: () => {
        cancelled = true;
      },
      eligible: () => !cancelled,
    });
    await service.runDue(100);
    expect(calls.messages).toHaveLength(0);
    expect(calls.finish[0]?.[1]).toBe('INVALIDATED');
  });

  it('fails closed with an explicit outcome for an invalid timezone', async () => {
    const invalid = preference({
      quietHours: { enabled: false, start: '22:00', end: '07:00', timeZone: 'Not/AZone' },
    });
    const { service, calls } = harness(reminder(), invalid);
    await service.runDue(100);
    expect(calls.messages).toHaveLength(0);
    expect(calls.finish[0]?.[1]).toBe('FAILED');
    expect(calls.finish[0]?.[3]).toBe('INVALID_TIME_ZONE');
  });

  it('keeps an unknown provider outcome on the same bounded retry identity', async () => {
    const { service, calls } = harness(reminder(), preference(), new UnknownProviderOutcomeError());
    await service.runDue(100);
    expect(calls.messages).toHaveLength(1);
    expect(calls.retry).toHaveLength(1);
    expect(calls.finish).toHaveLength(0);
  });

  it('records a terminal unknown outcome after the final ambiguous submission', async () => {
    const { service, calls } = harness(
      reminder({ attemptCount: 3 }),
      preference(),
      new UnknownProviderOutcomeError(),
    );
    await service.runDue(100);
    expect(calls.retry).toHaveLength(0);
    expect(calls.finish[0]?.[1]).toBe('UNKNOWN');
  });

  it('does not retry an explicit permanent provider rejection', async () => {
    const { service, calls } = harness(
      reminder(),
      preference(),
      new PermanentProviderRejectionError(),
    );
    await service.runDue(100);
    expect(calls.retry).toHaveLength(0);
    expect(calls.finish[0]?.[1]).toBe('FAILED');
  });
});
