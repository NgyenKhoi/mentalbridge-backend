import { describe, expect, it, vi } from 'vitest';

import { DailyReminderPushGate } from '../push-delivery/daily-reminder-push.gate.js';
import type { PushDeliveryClaim } from '../push-delivery/push-device.types.js';

const OWNER = '10000000-0000-4000-8000-000000000001';

const claim = (overrides: Partial<PushDeliveryClaim> = {}): PushDeliveryClaim =>
  ({
    id: 'attempt-1',
    kind: 'JOURNAL_REMINDER',
    ownerId: OWNER,
    timeZone: 'Asia/Ho_Chi_Minh',
    sourceIdentity: 'JOURNAL_REMINDER:2026-09-24',
    ...overrides,
  }) as PushDeliveryClaim;

function fixture(
  now: string,
  options: {
    time?: string;
    asOfLocalDate?: string;
    journalDone?: boolean;
    emotionDone?: boolean;
    fail?: boolean;
  } = {},
) {
  const get = vi.fn(async (_owner: string, _timezone: string, _correlationId: string) => {
    if (options.fail) throw new Error('journal-ai unavailable');
    return {
      asOfLocalDate: options.asOfLocalDate ?? '2026-09-24',
      journal: { completedToday: options.journalDone ?? false },
      emotionCheckIn: { completedToday: options.emotionDone ?? false },
    } as never;
  });
  const gate = new DailyReminderPushGate(
    { CONTENT_PUSH_DAILY_REMINDER_TIME: options.time ?? '20:00' } as never,
    { get },
    { now: () => new Date(now) },
  );
  return { gate, get };
}

describe('daily reminder push gate', () => {
  it('defers a reminder until the configured local time without reading activity', async () => {
    const { gate, get } = fixture('2026-09-24T12:59:00.000Z');

    await expect(gate.authorizeClaim(claim())).resolves.toEqual({
      decision: 'DEFER',
      availableAt: new Date('2026-09-24T13:00:00.000Z'),
      code: 'REMINDER_NOT_DUE',
    });
    expect(get).not.toHaveBeenCalled();
  });

  it('sends at the local time when the activity is still undone', async () => {
    const { gate, get } = fixture('2026-09-24T13:00:00.000Z');

    await expect(gate.authorizeClaim(claim())).resolves.toEqual({ decision: 'SEND' });
    expect(get).toHaveBeenCalledWith(OWNER, 'Asia/Ho_Chi_Minh', expect.any(String));
  });

  it('suppresses when that activity was completed, using the matching activity flag', async () => {
    const done = fixture('2026-09-24T13:00:00.000Z', { journalDone: true });
    await expect(done.gate.authorizeClaim(claim())).resolves.toEqual({
      decision: 'SUPPRESS',
      code: 'ACTIVITY_COMPLETED',
    });

    const otherActivityDone = fixture('2026-09-24T13:00:00.000Z', { emotionDone: true });
    await expect(otherActivityDone.gate.authorizeClaim(claim())).resolves.toEqual({
      decision: 'SEND',
    });
    await expect(
      otherActivityDone.gate.authorizeClaim(
        claim({
          kind: 'EMOTION_CHECKIN_REMINDER',
          sourceIdentity: 'EMOTION_CHECKIN_REMINDER:2026-09-24',
        }),
      ),
    ).resolves.toEqual({ decision: 'SUPPRESS', code: 'ACTIVITY_COMPLETED' });
  });

  it('drops a reminder once its local date has passed', async () => {
    const { gate, get } = fixture('2026-09-25T00:00:00.000Z');

    await expect(gate.authorizeClaim(claim())).resolves.toEqual({
      decision: 'SUPPRESS',
      code: 'REMINDER_EXPIRED',
    });
    expect(get).not.toHaveBeenCalled();
  });

  it('uses the reminder local date west of the server date', async () => {
    const eveningUtcNextDay = fixture('2026-09-25T03:30:00.000Z');
    const losAngeles = claim({ timeZone: 'America/Los_Angeles' });
    await expect(eveningUtcNextDay.gate.authorizeClaim(losAngeles)).resolves.toEqual({
      decision: 'SEND',
    });

    const beforeEvening = fixture('2026-09-25T02:00:00.000Z');
    await expect(beforeEvening.gate.authorizeClaim(losAngeles)).resolves.toEqual({
      decision: 'DEFER',
      availableAt: new Date('2026-09-25T03:00:00.000Z'),
      code: 'REMINDER_NOT_DUE',
    });

    const afterLocalMidnight = fixture('2026-09-25T07:30:00.000Z');
    await expect(afterLocalMidnight.gate.authorizeClaim(losAngeles)).resolves.toEqual({
      decision: 'SUPPRESS',
      code: 'REMINDER_EXPIRED',
    });
  });

  it('defers to 20:00 PDT on the spring-forward day', async () => {
    const la = claim({
      timeZone: 'America/Los_Angeles',
      sourceIdentity: 'JOURNAL_REMINDER:2026-03-08',
    });
    // local midnight 2026-03-08 = 08:00Z (PST); 20:00 PDT = 03:00Z next day
    const { gate } = fixture('2026-03-08T08:00:00.000Z', { asOfLocalDate: '2026-03-08' });
    await expect(gate.authorizeClaim(la)).resolves.toEqual({
      decision: 'DEFER',
      availableAt: new Date('2026-03-09T03:00:00.000Z'),
      code: 'REMINDER_NOT_DUE',
    });
    const due = fixture('2026-03-09T03:00:00.000Z', { asOfLocalDate: '2026-03-08' });
    await expect(due.gate.authorizeClaim(la)).resolves.toEqual({ decision: 'SEND' });
    const expired = fixture('2026-03-09T07:00:00.000Z', { asOfLocalDate: '2026-03-08' });
    await expect(expired.gate.authorizeClaim(la)).resolves.toEqual({
      decision: 'SUPPRESS',
      code: 'REMINDER_EXPIRED',
    });
  });

  it('defers to 20:00 PST on the fall-back day', async () => {
    const la = claim({
      timeZone: 'America/Los_Angeles',
      sourceIdentity: 'JOURNAL_REMINDER:2026-11-01',
    });
    // local midnight 2026-11-01 = 07:00Z (PDT); 20:00 PST = 04:00Z next day
    const { gate } = fixture('2026-11-01T07:00:00.000Z', { asOfLocalDate: '2026-11-01' });
    await expect(gate.authorizeClaim(la)).resolves.toEqual({
      decision: 'DEFER',
      availableAt: new Date('2026-11-02T04:00:00.000Z'),
      code: 'REMINDER_NOT_DUE',
    });
    const expired = fixture('2026-11-02T08:00:00.000Z', { asOfLocalDate: '2026-11-01' });
    await expect(expired.gate.authorizeClaim(la)).resolves.toEqual({
      decision: 'SUPPRESS',
      code: 'REMINDER_EXPIRED',
    });
  });

  it('defers five minutes when the activity source is unavailable', async () => {
    const { gate } = fixture('2026-09-24T13:10:00.000Z', { fail: true });

    await expect(gate.authorizeClaim(claim())).resolves.toEqual({
      decision: 'DEFER',
      availableAt: new Date('2026-09-24T13:15:00.000Z'),
      code: 'ACTIVITY_UNAVAILABLE',
    });
  });

  it('drops the reminder when activity was attributed to another local day', async () => {
    const { gate } = fixture('2026-09-24T13:00:00.000Z', { asOfLocalDate: '2026-09-25' });

    await expect(gate.authorizeClaim(claim())).resolves.toEqual({
      decision: 'SUPPRESS',
      code: 'REMINDER_EXPIRED',
    });
  });

  it('fails closed on a malformed source identity', async () => {
    const { gate, get } = fixture('2026-09-24T13:00:00.000Z');

    await expect(
      gate.authorizeClaim(claim({ sourceIdentity: 'JOURNAL_REMINDER:not-a-date' })),
    ).resolves.toEqual({ decision: 'SUPPRESS', code: 'REMINDER_EXPIRED' });
    expect(get).not.toHaveBeenCalled();
  });

  it('honours a custom reminder time', async () => {
    const early = fixture('2026-09-24T01:29:00.000Z', { time: '08:30' });
    await expect(early.gate.authorizeClaim(claim())).resolves.toEqual({
      decision: 'DEFER',
      availableAt: new Date('2026-09-24T01:30:00.000Z'),
      code: 'REMINDER_NOT_DUE',
    });
    const due = fixture('2026-09-24T01:30:00.000Z', { time: '08:30' });
    await expect(due.gate.authorizeClaim(claim())).resolves.toEqual({ decision: 'SEND' });
  });

  it('sends every other kind without any I/O', async () => {
    const { gate, get } = fixture('2026-09-24T01:00:00.000Z');

    await expect(
      gate.authorizeClaim(claim({ kind: 'APPOINTMENT', sourceIdentity: 'abc:1' })),
    ).resolves.toEqual({ decision: 'SEND' });
    expect(get).not.toHaveBeenCalled();
  });
});
