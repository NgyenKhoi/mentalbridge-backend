import { describe, expect, it } from 'vitest';

import {
  calculateResourceRewards,
  type ResourceRewardRule,
} from '../resources/resource-reward.policy.js';
import type { ResourceRewardSourceFact } from '../resources/resource-reward.types.js';

const fact = (
  resourceId: string,
  localDate: string,
  kind: ResourceRewardSourceFact['kind'] = 'RESOURCE_COMPLETION',
): ResourceRewardSourceFact => ({
  kind,
  resourceId,
  contentVersion: '3',
  localDate,
  occurredAt: `${localDate}T10:00:00.000Z`,
});

describe('resource reward policy', () => {
  it('deduplicates retries and same-day practice while applying the daily completion cap', () => {
    const facts = [
      fact('00000000-0000-4000-8000-000000000001', '2026-10-01'),
      fact('00000000-0000-4000-8000-000000000001', '2026-10-01'),
      fact('00000000-0000-4000-8000-000000000002', '2026-10-01'),
      fact('00000000-0000-4000-8000-000000000003', '2026-10-01'),
      fact('00000000-0000-4000-8000-000000000004', '2026-10-01'),
      fact('00000000-0000-4000-8000-000000000005', '2026-10-01'),
      fact('00000000-0000-4000-8000-000000000001', '2026-10-01', 'VERIFIED_PRACTICE'),
      fact('00000000-0000-4000-8000-000000000001', '2026-10-01', 'VERIFIED_PRACTICE'),
    ];
    const result = calculateResourceRewards(facts, 'owner-a');
    expect(result.totalXp).toBe(45);
    expect(result.grants).toHaveLength(5);
    expect(new Set(result.grants.map((grant) => grant.grantId)).size).toBe(5);
  });

  it('keeps historical grants on the rule effective for their local date', () => {
    const rules: readonly ResourceRewardRule[] = [
      {
        version: 'v1',
        effectiveFrom: '1970-01-01',
        completionXp: 10,
        practiceXp: 5,
        dailyCompletionCap: 4,
        levelThresholds: [0, 10, 40],
      },
      {
        version: 'v2',
        effectiveFrom: '2026-10-10',
        completionXp: 12,
        practiceXp: 6,
        dailyCompletionCap: 4,
        levelThresholds: [0, 12, 48],
      },
    ];
    const result = calculateResourceRewards(
      [
        fact('00000000-0000-4000-8000-000000000001', '2026-10-09'),
        fact('00000000-0000-4000-8000-000000000002', '2026-10-10'),
      ],
      'owner-a',
      rules,
    );
    expect(result.totalXp).toBe(22);
    expect(result.grants.map((grant) => grant.ruleVersion).sort()).toEqual(['v1', 'v2']);
    expect(result.policyVersion).toBe('v2');
  });

  it('returns an authoritative zero for no eligible persisted facts', () => {
    expect(calculateResourceRewards([], 'owner-a')).toMatchObject({
      totalXp: 0,
      level: 0,
      nextLevelXp: 10,
      policyVersion: 'resource-reward-v1',
      grants: [],
    });
  });

  it('is invariant under retries and source ordering across bounded generated histories', () => {
    for (let count = 1; count <= 24; count += 1) {
      const generated = Array.from({ length: count }, (_, index) =>
        fact(
          `00000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
          `2026-10-${String((index % 12) + 1).padStart(2, '0')}`,
          index % 3 === 0 ? 'VERIFIED_PRACTICE' : 'RESOURCE_COMPLETION',
        ),
      );
      const baseline = calculateResourceRewards(generated, 'owner-a');
      const retriedAndReordered = calculateResourceRewards(
        [...generated, ...generated].reverse(),
        'owner-a',
      );
      expect(retriedAndReordered.totalXp).toBe(baseline.totalXp);
      expect(retriedAndReordered.grants).toEqual(baseline.grants);
    }
  });

  it('binds grant identity to the authenticated owner', () => {
    const source = [fact('00000000-0000-4000-8000-000000000001', '2026-10-01')];
    const ownerA = calculateResourceRewards(source, 'owner-a');
    const ownerB = calculateResourceRewards(source, 'owner-b');
    expect(ownerA.grants[0].grantId).not.toBe(ownerB.grants[0].grantId);
  });
});
