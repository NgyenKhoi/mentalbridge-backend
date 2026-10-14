# ADR 0040: SupportPlan Companion product and interaction contract

- Status: Proposed for MB-647 review; accepted when this decision PR is approved and merged
- Date: 2026-10-14
- Decision ID: `MB-SUPPORT-PLAN-COMPANION-001`
- Product policy: [SupportPlan Companion policy v1](../policies/support-plan-companion-policy-v1.md)
- Depends on: [ADR 0034](0034-support-plan-v3-cross-domain-actions.md)
- Extends: [ADR 0021](0021-ai-companion-conversation-and-quota.md)
- Delivery sequence: MB-648 through MB-654

## Context

SupportPlan needs an optional companion that can explain approved plan facts,
help the user find the next relevant surface, and phrase encouragement without
becoming another plan owner. Without one shared contract, web, mobile and the
future message orchestrator could invent different intents, context fields,
interruption rules, or claims about progress.

ADR 0034 already makes Care authoritative for SupportPlan, PlanFocus,
PlanAction, schedules, occurrences, review outcomes and explicit user-confirmed
mutation. Journal/AI may phrase approved facts, while the frontend owns only
presentation and navigation. The companion must preserve those boundaries when
AI is slow, unavailable, quota-limited, malformed, or absent.

## Decision

The SupportPlan Companion is a calm, optional presentation, explanation and
navigation layer. It is not a therapist, clinician, safety classifier, plan
author, recommender with independent authority, or completion detector.

Every message request is selected by deterministic application policy before a
provider call. It uses one versioned intent and exactly one matching minimized
context variant from the canonical proposal schemas:

- [`support-plan-companion-intent-v1`](../../contracts/proposals/support-plan-companion-intent-v1.schema.json)
- [`support-plan-companion-context-v1`](../../contracts/proposals/support-plan-companion-context-v1.schema.json)

The v1 allowed intents are:

| Intent | Permitted result |
| --- | --- |
| `EXPLAIN_ACTION` | Explain the supplied approved action and how to continue through its authoritative flow. |
| `EXPLAIN_FOCUS` | Explain the supplied non-diagnostic focus wording and why its approved actions are grouped there. |
| `SUGGEST_NEXT_STEP` | Phrase one supplied eligible next step; it cannot rank or invent alternatives. |
| `ENCOURAGE` | Offer brief non-judgmental support tied to an explicit user action or approved state. |
| `SUMMARIZE_PROGRESS` | Summarize supplied factual counts/states without recovery, adherence or wellbeing scoring. |
| `EXPLAIN_REVIEW_OPTION` | Explain supplied Care review evidence and one or more admitted options without choosing for the user. |
| `NAVIGATE` | Explain and expose one supplied destination; navigation never proves completion or consent. |

Unknown intents, context variants, fields and enum values fail closed. The
schemas are proposals, not callable APIs. MB-649 owns the eventual REST/provider
contract, prompt/version selection, output schema, validation, deadline,
observability and deterministic fallback implementation.

## Authority and ownership

- Care selects and supplies authoritative SupportPlan facts, due states,
  review dimensions and admitted options.
- Journal/AI may phrase only the selected intent and supplied allowlisted
  context. It cannot query arbitrary owner data or return a business command.
- Web and mobile render the same intent, context, fallback and safety meaning.
  Platform differences are presentation-only.
- The user confirms plan, schedule, review, consent, booking and source-domain
  actions through their existing authoritative controls. Companion text or a
  free-text reply never performs those actions.
- Existing global safety and crisis-support controls always precede and remain
  available independently of the companion.

The companion cannot diagnose, impersonate a therapist, calculate clinical
severity, classify safety, determine eligibility, admit an action, infer
completion, score adherence/recovery, mutate a plan, book care, change consent,
consume credits, or create financial state.

## Interaction contract

Today, Plan, Progress and Adjust use the trigger, placement, interruption,
frequency, accessibility and responsive rules in the product policy. Model
calls are user-initiated in v1. Route entry, focus, hover, elapsed time and
background refresh may change a deterministic visual state but never send AI
context by themselves.

The character has seven presentation states: `idle`, `greeting`, `thinking`,
`encouraging`, `celebrating-light`, `calm-empathetic` and `waiting`. They express
UI activity, never an inferred mental state. Every state has equivalent text;
reduced motion removes repeated movement without removing guidance.

Contextual guidance may use an anchored bubble or inline hint on desktop and an
in-flow tray on smaller screens. A user-opened deeper conversation uses a side
panel on desktop and an accessible bottom/full-height sheet on mobile. These
surfaces cannot cover primary actions, dialogs, safety controls or sensitive
inputs, move focus without user action, or trap focus.

## Privacy, retention and observability

The model-facing allowlist contains bounded display facts and closed codes only.
It excludes raw Journal text, raw chat, emotion value/note, assessment answers
or raw scores, consultation notes/messages, identifiers not needed for phrasing,
credentials, provider payloads and arbitrary page state.

Anchored messages are ephemeral and do not become SupportPlan history. A later
expanded conversation may persist only through the existing consented
Journal/AI conversation boundary and retention policy; opening a panel does not
grant consent. Telemetry contains contract/prompt/provider versions, intent,
surface, outcome, latency and correlation identifiers, never the context or
generated/user text.

## Failure behavior

Template copy for each intent remains available without AI. Timeout, quota,
provider failure, malformed output, denied context or policy rejection returns
the deterministic fallback or no companion message. The authoritative
SupportPlan surface and its controls remain usable. A dependency failure cannot
change, disable or roll back plan state.

## Alternatives rejected

- A general unrestricted chatbot inside SupportPlan was rejected because it
  cannot guarantee bounded context, intent or mutation authority.
- Client-selected arbitrary context was rejected because web and mobile could
  leak different sensitive payloads or create a second policy.
- Automatic model calls on page entry, focus or hover were rejected because
  they are intrusive, costly and can process context without clear user intent.
- AI-selected actions, review outcomes, completion or safety responses were
  rejected because their owners are deterministic Care/source-domain flows.
- Persisting bubble text as plan evidence was rejected because phrasing is not
  an authoritative fact.

## Consequences

- MB-648 may implement only the reusable presentation shell and visual state
  mapping before message orchestration exists.
- MB-649 must implement the schemas and policy without widening context or
  adding mutation endpoints.
- MB-650 through MB-654 can vary layout by surface/platform but cannot fork
  intents, authority, frequency, privacy or fallback semantics.
- New intents, context fields, automatic triggers, retention or mutation
  capabilities require a new schema/policy version and explicit review.
