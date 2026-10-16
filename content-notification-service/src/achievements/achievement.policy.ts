import type {
  AchievementActivityFact,
  AchievementDomain,
  AchievementEvidenceClass,
  AchievementItem,
  AchievementKind,
  AchievementResourceFact,
  AchievementTier,
} from './achievement.types.js';

export const ACHIEVEMENT_POLICY_VERSION = 'achievement-rules-v1' as const;

interface Definition {
  readonly definitionId: string;
  readonly domain: AchievementDomain;
  readonly kind: AchievementKind;
  readonly tier: AchievementTier;
  readonly title: string;
  readonly description: string;
  readonly evidenceClass: AchievementEvidenceClass;
  readonly target: number;
  readonly unit: NonNullable<AchievementItem['progress']>['unit'];
}

type PublicDefinition = Pick<
  AchievementItem,
  'definitionId' | 'domain' | 'kind' | 'tier' | 'title' | 'description' | 'evidenceClass'
>;

export const ACHIEVEMENT_DEFINITIONS: readonly Definition[] = [
  {
    definitionId: 'journal.first-entry',
    domain: 'JOURNAL',
    kind: 'MILESTONE',
    tier: 'FIRST_STEP',
    title: 'Trang viết đầu tiên',
    description: 'Một trang Nhật ký riêng tư đã được lưu.',
    evidenceClass: 'OWNER_SOURCE_FACT',
    target: 1,
    unit: 'EVENT',
  },
  {
    definitionId: 'journal.streak-3-days',
    domain: 'JOURNAL',
    kind: 'CONSISTENCY',
    tier: 'STEADY_STEP',
    title: 'Một nhịp viết ba ngày',
    description: 'Ba ngày viết liền nhau đã từng được ghi nhận. Bạn không cần duy trì chuỗi.',
    evidenceClass: 'OWNER_STREAK_MILESTONE',
    target: 3,
    unit: 'CONSECUTIVE_DAY',
  },
  {
    definitionId: 'journal.streak-7-days',
    domain: 'JOURNAL',
    kind: 'CONSISTENCY',
    tier: 'DEEPENING_STEP',
    title: 'Một nhịp viết bảy ngày',
    description: 'Bảy ngày viết liền nhau đã từng được ghi nhận. Nghỉ ngơi không làm mất dấu mốc.',
    evidenceClass: 'OWNER_STREAK_MILESTONE',
    target: 7,
    unit: 'CONSECUTIVE_DAY',
  },
  {
    definitionId: 'emotion.first-check-in',
    domain: 'EMOTION_CHECK_IN',
    kind: 'MILESTONE',
    tier: 'FIRST_STEP',
    title: 'Lắng nghe cảm xúc đầu tiên',
    description: 'Một lần check-in cảm xúc đã được lưu.',
    evidenceClass: 'OWNER_SOURCE_FACT',
    target: 1,
    unit: 'EVENT',
  },
  {
    definitionId: 'emotion.streak-3-days',
    domain: 'EMOTION_CHECK_IN',
    kind: 'CONSISTENCY',
    tier: 'STEADY_STEP',
    title: 'Ba ngày lắng nghe cảm xúc',
    description:
      'Ba ngày check-in liền nhau đã từng được ghi nhận. Đây không phải thước đo hồi phục.',
    evidenceClass: 'OWNER_STREAK_MILESTONE',
    target: 3,
    unit: 'CONSECUTIVE_DAY',
  },
  {
    definitionId: 'emotion.streak-7-days',
    domain: 'EMOTION_CHECK_IN',
    kind: 'CONSISTENCY',
    tier: 'DEEPENING_STEP',
    title: 'Bảy ngày lắng nghe cảm xúc',
    description: 'Bảy ngày check-in liền nhau đã từng được ghi nhận, không kèm đánh giá cảm xúc.',
    evidenceClass: 'OWNER_STREAK_MILESTONE',
    target: 7,
    unit: 'CONSECUTIVE_DAY',
  },
  {
    definitionId: 'resource.first-completion',
    domain: 'RESOURCE',
    kind: 'MILESTONE',
    tier: 'FIRST_STEP',
    title: 'Tài nguyên đầu tiên đã hoàn thành',
    description: 'Một tài nguyên đã duyệt đã được bạn xác nhận hoàn thành.',
    evidenceClass: 'OWNER_SOURCE_FACT',
    target: 1,
    unit: 'EVENT',
  },
  {
    definitionId: 'resource.variety-3',
    domain: 'RESOURCE',
    kind: 'MILESTONE',
    tier: 'STEADY_STEP',
    title: 'Khám phá ba tài nguyên khác nhau',
    description: 'Ba tài nguyên đã duyệt khác nhau đã được hoàn thành.',
    evidenceClass: 'OWNER_SOURCE_FACT',
    target: 3,
    unit: 'DISTINCT_RESOURCE',
  },
  {
    definitionId: 'resource.practice-3-days',
    domain: 'RESOURCE',
    kind: 'CONSISTENCY',
    tier: 'STEADY_STEP',
    title: 'Thực hành trong ba ngày khác nhau',
    description: 'Ba ngày thực hành được xác minh, không yêu cầu liên tục.',
    evidenceClass: 'OWNER_PRACTICE_WINDOW',
    target: 3,
    unit: 'PRACTICE_DAY',
  },
  {
    definitionId: 'resource.practice-7-days',
    domain: 'RESOURCE',
    kind: 'CONSISTENCY',
    tier: 'DEEPENING_STEP',
    title: 'Thực hành trong bảy ngày khác nhau',
    description: 'Bảy ngày thực hành được xác minh, theo nhịp độ của bạn.',
    evidenceClass: 'OWNER_PRACTICE_WINDOW',
    target: 7,
    unit: 'PRACTICE_DAY',
  },
] as const;

function publicDefinition(definition: Definition): PublicDefinition {
  return {
    definitionId: definition.definitionId,
    domain: definition.domain,
    kind: definition.kind,
    tier: definition.tier,
    title: definition.title,
    description: definition.description,
    evidenceClass: definition.evidenceClass,
  };
}

function unavailable(definition: Definition): AchievementItem {
  return {
    ...publicDefinition(definition),
    status: 'SOURCE_UNAVAILABLE',
    unlockedAt: null,
    ruleVersion: ACHIEVEMENT_POLICY_VERSION,
    privacy: 'PRIVATE',
    progress: null,
  };
}

function activityItem(definition: Definition, fact: AchievementActivityFact): AchievementItem {
  const milestone = fact.streakMilestones.find(({ days }) => days === definition.target);
  const unlockedAt =
    definition.kind === 'MILESTONE' ? fact.firstOccurredAt : (milestone?.reachedAt ?? null);
  const current =
    definition.kind === 'MILESTONE'
      ? Math.min(fact.distinctDays, 1)
      : Math.min(fact.longestStreak, definition.target);
  return {
    ...publicDefinition(definition),
    status: unlockedAt ? 'EARNED' : 'UNEARNED',
    unlockedAt,
    ruleVersion: ACHIEVEMENT_POLICY_VERSION,
    privacy: 'PRIVATE',
    progress: { current, target: definition.target, unit: definition.unit },
  };
}

function nthOccurredAt(values: readonly AchievementResourceFact[], target: number): string | null {
  return values[target - 1]?.occurredAt ?? null;
}

export function calculateAchievements(input: {
  readonly activity: {
    readonly journal: AchievementActivityFact;
    readonly emotionCheckIn: AchievementActivityFact;
  } | null;
  readonly resourceFacts: readonly AchievementResourceFact[];
}): readonly AchievementItem[] {
  const completionByResource = new Map<string, AchievementResourceFact>();
  const practiceByDay = new Map<string, AchievementResourceFact>();
  for (const fact of [...input.resourceFacts].sort((left, right) =>
    left.occurredAt.localeCompare(right.occurredAt),
  )) {
    if (fact.kind === 'RESOURCE_COMPLETION' && !completionByResource.has(fact.resourceId)) {
      completionByResource.set(fact.resourceId, fact);
    }
    if (fact.kind === 'VERIFIED_PRACTICE' && !practiceByDay.has(fact.localDate)) {
      practiceByDay.set(fact.localDate, fact);
    }
  }
  const completions = [...completionByResource.values()];
  const practices = [...practiceByDay.values()];

  const items = ACHIEVEMENT_DEFINITIONS.map((definition): AchievementItem => {
    if (definition.domain === 'JOURNAL' || definition.domain === 'EMOTION_CHECK_IN') {
      if (!input.activity) return unavailable(definition);
      return activityItem(
        definition,
        definition.domain === 'JOURNAL' ? input.activity.journal : input.activity.emotionCheckIn,
      );
    }
    const values = definition.unit === 'PRACTICE_DAY' ? practices : completions;
    const unlockedAt = nthOccurredAt(values, definition.target);
    return {
      ...publicDefinition(definition),
      status: unlockedAt ? 'EARNED' : 'UNEARNED',
      unlockedAt,
      ruleVersion: ACHIEVEMENT_POLICY_VERSION,
      privacy: 'PRIVATE',
      progress: {
        current: Math.min(values.length, definition.target),
        target: definition.target,
        unit: definition.unit,
      },
    };
  });
  return [
    ...items,
    {
      definitionId: 'journey.new-chapter',
      domain: 'JOURNEY',
      kind: 'JOURNEY',
      tier: 'JOURNEY',
      title: 'Một chương mới',
      description:
        'Mốc này chỉ mở sau khi bạn chủ động xác nhận một sự kiện phù hợp trong Kế hoạch hỗ trợ.',
      status: 'DEFERRED',
      evidenceClass: 'USER_CONFIRMED_JOURNEY',
      unlockedAt: null,
      ruleVersion: ACHIEVEMENT_POLICY_VERSION,
      privacy: 'PRIVATE',
      progress: null,
    },
  ];
}
