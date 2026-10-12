import { createHash } from 'node:crypto';

import type { ResourceRewardGrant, ResourceRewardSourceFact } from './resource-reward.types.js';

export interface ResourceRewardRule {
  readonly version: string;
  readonly effectiveFrom: string;
  readonly completionXp: number;
  readonly practiceXp: number;
  readonly dailyCompletionCap: number;
  readonly levelThresholds: readonly number[];
}

export const RESOURCE_REWARD_RULES: readonly ResourceRewardRule[] = [
  {
    version: 'resource-reward-v1',
    effectiveFrom: '1970-01-01',
    completionXp: 10,
    practiceXp: 5,
    dailyCompletionCap: 4,
    levelThresholds: [0, 10, 40, 90, 160, 250],
  },
];

function applicableRule(
  localDate: string,
  rules: readonly ResourceRewardRule[],
): ResourceRewardRule | null {
  return (
    [...rules]
      .filter((rule) => rule.effectiveFrom <= localDate)
      .sort((left, right) => right.effectiveFrom.localeCompare(left.effectiveFrom))[0] ?? null
  );
}

function identity(fact: ResourceRewardSourceFact, ruleVersion: string): string {
  return createHash('sha256')
    .update(
      [fact.kind, fact.resourceId, fact.contentVersion, fact.localDate, ruleVersion].join(':'),
    )
    .digest('hex');
}

export function calculateResourceRewards(
  facts: readonly ResourceRewardSourceFact[],
  ownerId: string,
  rules: readonly ResourceRewardRule[] = RESOURCE_REWARD_RULES,
): {
  readonly totalXp: number;
  readonly level: number;
  readonly nextLevelXp: number | null;
  readonly policyVersion: string;
  readonly grants: readonly ResourceRewardGrant[];
} {
  const deduplicated = new Map<string, ResourceRewardSourceFact>();
  for (const fact of facts) {
    const key = [fact.kind, fact.resourceId, fact.contentVersion, fact.localDate].join(':');
    const existing = deduplicated.get(key);
    if (!existing || fact.occurredAt < existing.occurredAt) deduplicated.set(key, fact);
  }

  const completionCounts = new Map<string, number>();
  const orderedFacts = [...deduplicated.values()].sort(
    (left, right) =>
      left.occurredAt.localeCompare(right.occurredAt) ||
      left.resourceId.localeCompare(right.resourceId) ||
      left.kind.localeCompare(right.kind),
  );
  const grants: ResourceRewardGrant[] = [];
  for (const fact of orderedFacts) {
    const rule = applicableRule(fact.localDate, rules);
    if (!rule) continue;
    if (fact.kind === 'RESOURCE_COMPLETION') {
      const count = completionCounts.get(fact.localDate) ?? 0;
      if (count >= rule.dailyCompletionCap) continue;
      completionCounts.set(fact.localDate, count + 1);
    }
    const xp = fact.kind === 'RESOURCE_COMPLETION' ? rule.completionXp : rule.practiceXp;
    grants.push({
      grantId: createHash('sha256')
        .update(`${ownerId}:${identity(fact, rule.version)}`)
        .digest('hex'),
      kind: fact.kind,
      xp,
      resourceId: fact.resourceId,
      contentVersion: fact.contentVersion,
      localDate: fact.localDate,
      grantedAt: fact.occurredAt,
      sourceReference: `${fact.kind === 'RESOURCE_COMPLETION' ? 'resource-progress' : 'resource-practice'}:${fact.resourceId}:${fact.localDate}`,
      ruleVersion: rule.version,
    });
  }

  const totalXp = grants.reduce((total, grant) => total + grant.xp, 0);
  const currentRule = [...rules].sort((left, right) =>
    right.effectiveFrom.localeCompare(left.effectiveFrom),
  )[0];
  const thresholds = currentRule.levelThresholds;
  let level = 0;
  for (let index = 0; index < thresholds.length; index += 1) {
    if (totalXp >= thresholds[index]) level = index;
  }
  return {
    totalXp,
    level,
    nextLevelXp: thresholds[level + 1] ?? null,
    policyVersion: currentRule.version,
    grants: grants.sort(
      (left, right) =>
        right.grantedAt.localeCompare(left.grantedAt) || right.grantId.localeCompare(left.grantId),
    ),
  };
}
