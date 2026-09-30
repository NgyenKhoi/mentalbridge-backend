import { describe, expect, it, vi } from 'vitest';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { NotificationPreferences } from '../notification-preferences/notification-preference.types.js';
import { WellbeingDigestScheduler } from '../wellbeing-digest/wellbeing-digest.scheduler.js';

const preferences = {} as NotificationPreferences;
const configuration = {
  WELLBEING_DIGEST_SCHEDULER_ENABLED: true,
  WELLBEING_DIGEST_SCHEDULER_INTERVAL_MS: 60_000,
  WELLBEING_DIGEST_BATCH_SIZE: 2,
} as ServiceConfiguration;

describe('WellbeingDigestScheduler', () => {
  it('pages candidates and isolates one owner delivery failure', async () => {
    const preferenceService = {
      listEmailCandidates: vi
        .fn()
        .mockResolvedValueOnce([
          { ownerId: '00000000-0000-4000-8000-000000000001', preferences },
          { ownerId: '00000000-0000-4000-8000-000000000002', preferences },
        ])
        .mockResolvedValueOnce([{ ownerId: '00000000-0000-4000-8000-000000000003', preferences }]),
    };
    const digest = {
      deliver: vi
        .fn()
        .mockResolvedValueOnce(1)
        .mockRejectedValueOnce(new Error('provider unavailable'))
        .mockResolvedValueOnce(1),
    };
    const scheduler = new WellbeingDigestScheduler(
      configuration,
      preferenceService as never,
      digest as never,
    );

    await expect(scheduler.runOnce()).resolves.toEqual({
      candidates: 3,
      delivered: 2,
      failures: 1,
      skippedOverlap: false,
    });
    expect(preferenceService.listEmailCandidates).toHaveBeenNthCalledWith(
      2,
      '00000000-0000-4000-8000-000000000002',
      2,
    );
  });

  it('skips an overlapping scheduler run', async () => {
    let finish!: (value: readonly never[]) => void;
    const page = new Promise<readonly never[]>((resolve) => {
      finish = resolve;
    });
    const scheduler = new WellbeingDigestScheduler(
      configuration,
      { listEmailCandidates: vi.fn().mockReturnValue(page) } as never,
      { deliver: vi.fn() } as never,
    );

    const first = scheduler.runOnce();
    await expect(scheduler.runOnce()).resolves.toEqual({
      candidates: 0,
      delivered: 0,
      failures: 0,
      skippedOverlap: true,
    });
    finish([]);
    await expect(first).resolves.toEqual({
      candidates: 0,
      delivered: 0,
      failures: 0,
      skippedOverlap: false,
    });
  });
});
