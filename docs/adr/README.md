# MentalBridge ADR register

Use ADR **filename + Decision ID**, not numeric prefix alone, when a reference could be ambiguous.

## Current product-scope chain

| File | Decision ID | Status | Role |
| --- | --- | --- | --- |
| [0017-product-scope-v2.md](0017-product-scope-v2.md) | `MB-SCOPE-V2-001` | Accepted | Base cross-feature v2 scope |
| [0022-current-product-blueprint-amendments.md](0022-current-product-blueprint-amendments.md) | `MB-SCOPE-V2-002` | Accepted | Latest amendments for Support Guide semantics, reassessment, PlanChangeRequest handoff, consultation credits, no-rollover, and reservation caps |
| [0023-persist-guided-screening-episodes.md](0023-persist-guided-screening-episodes.md) | `MB-SCREENING-EPISODE-001` | Accepted | Care-owned exact grouping and resume context for guided initial check and reassessment evidence |

For clauses explicitly amended by ADR 0022, ADR 0022 is the current prospective authority. Historical records keep their original policy/version.

## Known numeric-prefix collisions

The repository historically assigned the same numeric prefix to multiple accepted decisions. Renaming these files would break existing references and provenance, so they remain in place. New ADRs must use a new unused numeric prefix.

| Numeric prefix | File | Decision ID |
| --- | --- | --- |
| `0018` | [0018-daily-emotion-check-in.md](0018-daily-emotion-check-in.md) | `MB-DAILY-EMOTION-CHECK-IN-001` |
| `0018` | [0018-reviewed-vietnam-safety-directory.md](0018-reviewed-vietnam-safety-directory.md) | `MB-VN-SAFETY-DIRECTORY-001` |
| `0020` | [0020-service-plan-consultation-credits.md](0020-service-plan-consultation-credits.md) | `MB-CONSULTATION-CREDIT-001` |
| `0020` | [0020-support-plan-activity-occurrence-scheduling.md](0020-support-plan-activity-occurrence-scheduling.md) | `MB-SUPPORT-PLAN-ACTIVITY-SCHEDULE-001` |
| `0021` | [0021-ai-companion-conversation-and-quota.md](0021-ai-companion-conversation-and-quota.md) | `MB-AI-COMPANION-CHAT-001` |
| `0021` | [0021-support-plan-engagement.md](0021-support-plan-engagement.md) | `MB-SUPPORT-PLAN-ENGAGEMENT-001` |

Do not create another ADR using `0018`, `0020`, or `0021`. Do not write a bare reference such as “ADR 0021” in new documentation; link the exact filename and include the Decision ID where the distinction matters.

## Decision precedence

For product/business rules:

1. follow the latest explicit accepted ADR/amendment that covers the rule;
2. follow the current approved domain policy implementing that decision;
3. follow versioned contracts and owner migrations;
4. use domain/architecture docs for integrated explanation;
5. use implementation/tests as runtime evidence;
6. use Jira for delivery tracking, not to redefine business authority.

See [Current Product Blueprint](../CURRENT_PRODUCT_BLUEPRINT.md) for the cross-feature authority map.
