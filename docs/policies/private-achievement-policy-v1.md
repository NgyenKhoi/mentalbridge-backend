# Private achievement policy v1

| Field | Value |
| --- | --- |
| Policy version | `achievement-rules-v1` |
| Decision | [ADR 0038](../adr/0038-private-multi-domain-achievements.md), `MB-PRIVATE-ACHIEVEMENT-001` |
| Collection owner | Content/Notification Service |
| Source owners | Journal/AI for Journal and emotion; Content/Notification for Resources |
| Visibility | Private owner-only, visible by default, reversibly hideable |

## Frozen definitions

| Definition ID | Domain | Tier | Requirement | Evidence class |
| --- | --- | --- | --- | --- |
| `journal.first-entry` | Journal | `FIRST_STEP` | First active private Journal entry | `OWNER_SOURCE_FACT` |
| `journal.streak-3-days` | Journal | `STEADY_STEP` | First factual three-day writing streak | `OWNER_STREAK_MILESTONE` |
| `journal.streak-7-days` | Journal | `DEEPENING_STEP` | First factual seven-day writing streak | `OWNER_STREAK_MILESTONE` |
| `emotion.first-check-in` | Emotion | `FIRST_STEP` | First active emotion check-in | `OWNER_SOURCE_FACT` |
| `emotion.streak-3-days` | Emotion | `STEADY_STEP` | First factual three-day check-in streak | `OWNER_STREAK_MILESTONE` |
| `emotion.streak-7-days` | Emotion | `DEEPENING_STEP` | First factual seven-day check-in streak | `OWNER_STREAK_MILESTONE` |
| `resource.first-completion` | Resource | `FIRST_STEP` | First completed reviewed Resource | `OWNER_SOURCE_FACT` |
| `resource.variety-3` | Resource | `STEADY_STEP` | Three distinct completed reviewed Resources | `OWNER_SOURCE_FACT` |
| `resource.practice-3-days` | Resource | `STEADY_STEP` | Verified practice on three distinct local days | `OWNER_PRACTICE_WINDOW` |
| `resource.practice-7-days` | Resource | `DEEPENING_STEP` | Verified practice on seven distinct local days | `OWNER_PRACTICE_WINDOW` |
| `journey.new-chapter` | Journey | `JOURNEY` | Future explicit owner confirmation from a suitable SupportPlan v3 event | `USER_CONFIRMED_JOURNEY` |

Streak achievements recognize a historical bounded fact and never require the
current streak to continue. Missing a day cannot revoke, penalize, or reduce
access. Resource practice windows are distinct days and are not streaks.

## State, correction, and privacy

`EARNED` requires exact source evidence and includes its first valid unlock
instant. `UNEARNED` includes only bounded factual progress. `GRANDFATHERED`
preserves an award earned under an older frozen rule version after a later rule
version replaces it; version 1 does not currently emit this state.
`SOURCE_UNAVAILABLE`
hides progress and unlock time rather than displaying stale values. `DEFERRED`
has no current eligibility path. Future rule changes require a new rule version;
they do not overwrite this taxonomy.

Active source deletion or correction is authoritative on the next read. A fact
that remains valid keeps its original source instant. There is no clinical,
emotion-quality, superiority, leaderboard, rank, penalty, notification pressure,
or entitlement effect. Hiding the gallery changes presentation only.
