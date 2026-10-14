# SupportPlan Companion v1 proposal

## Ownership and requirement

- Requirement: Jira MB-647
- Decision: `MB-SUPPORT-PLAN-COMPANION-001` in
  [ADR 0040](../../docs/adr/0040-support-plan-companion-contract.md)
- Product/fact owner: Care owns SupportPlan facts, eligibility, review options
  and mutations; each source domain retains its own evidence.
- Phrasing/runtime owner: Journal/AI may phrase one deterministic intent from
  one minimized context after MB-649 implements and approves a callable API.
- Client owners: web and mobile render the shared interaction contract without
  creating facts, intents or provider requests outside the policy.

## Proposed contracts

- [`support-plan-companion-context-v1.schema.json`](support-plan-companion-context-v1.schema.json)
  closes the model-facing allowlist into seven discriminated variants.
- [`support-plan-companion-intent-v1.schema.json`](support-plan-companion-intent-v1.schema.json)
  binds policy version, user-initiated source, surface, intent and matching
  context variant.

These language-neutral schemas are not an HTTP, Kafka or WebSocket contract.
They deliberately contain no plan/action/account identifier because
authorization, correlation and navigation identity stay in the deterministic
application boundary and never become model instructions.

## Open delivery decisions

MB-649 must review and freeze the callable transport, authorization, quota,
timeout, prompt/template revision, normalized response schema, output
validation, deterministic fallback selection, observability and retention
mechanics. MB-650 through MB-654 must supply implementation evidence for the
placements, expanded flow, review explanation, release behavior and mobile
parity. None of those stories may broaden the v1 persona, intent set, context
allowlist, authority or interruption rules without a reviewed contract version.

## Compatibility

V1 clients and servers reject unknown fields, unknown versions and mismatched
surface/intent/context combinations. An additive intent, context field,
automatic provider trigger, retention use or authority change requires v2.
Copy-only revisions that preserve the frozen semantics use a separate
template/prompt version under MB-649.

Existing SupportPlan APIs, events and records are unchanged. The companion can
be absent, disabled or unavailable while Today, Plan, Progress and Adjust keep
their normal authoritative controls.

## Acceptance examples

Positive and negative envelopes are recorded in
[MB-647 evidence](../../docs/story-mb-647-support-plan-companion-contract-evidence.md).
Validation must accept the documented user-initiated action and partial
progress examples. It must reject wrong surface/intent pairs, wrong
intent/context pairs, automatic request sources, unknown fields and raw
identity/Journal/emotion/screening/consultation/provider data.
