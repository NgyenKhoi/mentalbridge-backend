# MentalBridge ADR register

Use ADR **filename + Decision ID**, not numeric prefix alone, when a reference could be ambiguous.

## Current product-scope chain

MB-684 accepted [0042-community-chat-contract.md](0042-community-chat-contract.md)
(`MB-COMMUNITY-CHAT-001`) in merged PR #206. It scopes Community-owned private
peer conversations separately from appointment chat. MB-685/687/689/690/691
implement their active contract operations; remaining proposal operations are
still planned. Native-only credential ingress is the scoped
[ADR 0043](0043-native-community-credential-exchange.md) prerequisite for MB-692.

| File | Decision ID | Status | Role |
| --- | --- | --- | --- |
| [0017-product-scope-v2.md](0017-product-scope-v2.md) | `MB-SCOPE-V2-001` | Accepted | Base cross-feature v2 scope |
| [0022-current-product-blueprint-amendments.md](0022-current-product-blueprint-amendments.md) | `MB-SCOPE-V2-002` | Accepted | Latest amendments for Support Guide semantics, reassessment, PlanChangeRequest handoff, consultation credits, no-rollover, and reservation caps |
| [0023-persist-guided-screening-episodes.md](0023-persist-guided-screening-episodes.md) | `MB-SCREENING-EPISODE-001` | Accepted | Care-owned exact grouping and resume context for guided initial check and reassessment evidence |
| [0024-global-anonymous-crisis-support.md](0024-global-anonymous-crisis-support.md) | `MB-GLOBAL-CRISIS-SUPPORT-001` | Accepted | Latest safety amendment for the global anonymous panel and reviewed national `112`/`115` contacts |
| [0025-authoritative-emotion-check-in-progress.md](0025-authoritative-emotion-check-in-progress.md) | `MB-EMOTION-CHECK-IN-PROGRESS-001` | Accepted | Journal/AI-owned factual emotion history, streak, and 7/14/30-day read-model semantics |
| [0026-journal-emotion-notification-materialization.md](0026-journal-emotion-notification-materialization.md) | `MB-WELLBEING-NOTIFICATION-001` | Accepted | Owner-authorized Journal/emotion activity projection and Content-owned in-app reminder/milestone materialization |
| [0027-approved-online-specialist-discovery.md](0027-approved-online-specialist-discovery.md) | `MB-SPECIALIST-DISCOVERY-001` | Accepted | Approved-only online specialist discovery, privacy-minimized deterministic ranking, entitlement boundary, and exact selectable-slot handoff |
| [0028-community-service-boundary.md](0027-community-service-boundary.md) | `MB-COMMUNITY-SERVICE-001` | Accepted | Independent Community owner, Spring/PostgreSQL runtime, and privacy-isolated foundation |
| [0029-evidence-backed-chat-session-completion.md](0029-evidence-backed-chat-session-completion.md) | `MB-CHAT-SESSION-COMPLETION-001` | Accepted | Server-evidenced in-app chat completion, no-show outcomes, grace/reconciliation, and credit settlement |
| [0030-one-appointment-email-reminder.md](0030-one-appointment-email-reminder.md) | `MB-APPOINTMENT-EMAIL-REMINDER-001` | Accepted | One user-owned appointment email with fixed timing, lifecycle invalidation, preference/quiet-hour rules, minimized content, and strict before-start delivery |
| [0031-appointment-dispute-settlement-gate.md](0031-appointment-dispute-settlement-gate.md) | `MB-APPOINTMENT-DISPUTE-001` | Accepted | Minimized participant dispute, earning gate, immutable resolution, and explicit credit adjustment |
| [0032-payment-and-specialist-payout-baseline.md](0032-payment-and-specialist-payout-baseline.md) | `MB-PAYMENT-PAYOUT-BASELINE-001` | Accepted | Separates production-planned economics from the 0/5k/10k runtime test catalogue and fixes earning, hold, withdrawal, fake-adapter, and MoMo sandbox rules |
| [0033-reviewed-specialist-profile-amendment.md](0033-reviewed-specialist-profile-amendment.md) | `MB-SPECIALIST-PROFILE-AMENDMENT-001` | Accepted product direction | Keeps approved profiles public while private amendments undergo separate ADMIN review |
| [0034-support-plan-v3-cross-domain-actions.md](0034-support-plan-v3-cross-domain-actions.md) | `MB-SUPPORT-PLAN-V3-ACTIONS-001` | Accepted | Replaces Resource-as-parent composition prospectively with Care-owned focuses, typed actions, generic schedules/occurrences, bounded source/evidence references, and a non-destructive v2 read adapter |
| [0035-optional-emotion-influence-context.md](0035-optional-emotion-influence-context.md) | `MB-EMOTION-CONTEXT-001` | Accepted | Adds bounded optional encrypted influence factors and private note UX while prohibiting downstream reuse without a separate policy |
| [0036-authoritative-resource-rewards.md](0036-authoritative-resource-rewards.md) | `MB-RESOURCE-REWARD-001` | Accepted | Derives optional non-punitive XP from exact Content evidence with versioned rules and no second award ledger |
| [0037-persistent-resource-engagement.md](0037-persistent-resource-engagement.md) | `MB-RESOURCE-ENGAGEMENT-001` | Accepted | Persists exact-version bookmarks and separates reversible Bingo self-confirmation from recomputed Content evidence |
| [0038-private-multi-domain-achievements.md](0038-private-multi-domain-achievements.md) | `MB-PRIVATE-ACHIEVEMENT-001` | Accepted | Defines a private deterministic gallery over minimized owner facts with explicit unavailable/deferred states and no inferred journey closure |
| [0039-livekit-cloud-video-consultation.md](0039-livekit-cloud-video-consultation.md) | `MB-LIVEKIT-VIDEO-001` | Accepted provider/access/privacy/retention/completion command; automatic timeout settlement not approved | LiveKit Cloud transport with appointment-scoped tokens, verified minimized evidence and 90-day hold-aware retention |
| [0040-support-plan-companion-contract.md](0040-support-plan-companion-contract.md) | `MB-SUPPORT-PLAN-COMPANION-001` | Proposed for MB-647 review | Freezes the optional SupportPlan Companion role, intent/context boundary, interaction states, interruption rules, deterministic fallback and cross-client UX contract |
| [0041-specialist-credential-review.md](0041-specialist-credential-review.md) | `MB-SPECIALIST-CREDENTIAL-001` | Accepted direction | Adds private specialist credentials, ADMIN verification, owner-controlled badges, and `zh`/`fr` profile languages |
| [0042-community-chat-contract.md](0042-community-chat-contract.md) | `MB-COMMUNITY-CHAT-001` | Accepted | Community-owned private peer conversations and isolated Realtime transport |
| [0043-native-community-credential-exchange.md](0043-native-community-credential-exchange.md) | `MB-COMMUNITY-NATIVE-CREDENTIAL-001` | Owner accepted; provider implemented MB-749 | Identity-authenticated native exchange preserving Community owner authorization and existing BFF tickets |
| [0043-plan-scoped-resource-progress.md](0043-plan-scoped-resource-progress.md) | `MB-PLAN-RESOURCE-PROGRESS-001` | Accepted for MB-670 implementation | Care-owned minimized plan resources, Content-owned plan-scoped Bingo and exact-version completion facts, and bounded Care occurrence reconciliation |

For clauses explicitly amended by ADR 0022, ADR 0024, ADR 0034, or [0043-plan-scoped-resource-progress.md](0043-plan-scoped-resource-progress.md), the relevant
latest amendment is the current prospective authority. Historical records keep
their original policy/version.

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
| `0043` | [0043-native-community-credential-exchange.md](0043-native-community-credential-exchange.md) | `MB-COMMUNITY-NATIVE-CREDENTIAL-001` |
| `0043` | [0043-plan-scoped-resource-progress.md](0043-plan-scoped-resource-progress.md) | `MB-PLAN-RESOURCE-PROGRESS-001` |

Do not create another ADR using `0018`, `0020`, `0021`, or `0043`. Do not write a bare reference such as “ADR 0021” in new documentation; link the exact filename and include the Decision ID where the distinction matters.

## Decision precedence

For product/business rules:

1. follow the latest explicit accepted ADR/amendment that covers the rule;
2. follow the current approved domain policy implementing that decision;
3. follow versioned contracts and owner migrations;
4. use domain/architecture docs for integrated explanation;
5. use implementation/tests as runtime evidence;
6. use Jira for delivery tracking, not to redefine business authority.

See [Current Product Blueprint](../CURRENT_PRODUCT_BLUEPRINT.md) for the cross-feature authority map.
