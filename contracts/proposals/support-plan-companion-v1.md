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

MB-649 reuses these language-neutral schemas unchanged as the request body of
the callable Journal/AI REST contract. They deliberately contain no
plan/action/account identifier because
authorization, correlation and navigation identity stay in the deterministic
application boundary and never become model instructions.

## MB-649 runtime decisions

The callable transport is authenticated REST at
`POST /internal/v1/support-plan-companion/messages`. Care supplies its dedicated
service credential, forwards the verified end-user bearer, and sends one
deterministic envelope. A USER bearer alone is rejected before validation,
consent or provider access. Journal/AI rejects unknown fields and
surface/intent/context mismatches, checks current AI consent, invokes only the
configured approved provider route, and validates a bounded text-only result.
Provider timeout, rate/quota rejection, unavailability, malformed output and
policy rejection return in-contract `support-plan-companion-template-v1` copy.
Progress phrasing must preserve the ordered completed/available relation and
partial-evidence meaning. Requests and responses are ephemeral. Telemetry
contains only the allowlisted versions, intent, surface, outcome, latency,
fallback reason and correlation/provider identifiers.

MB-650 through MB-654 must supply implementation evidence for the placements,
expanded flow, review explanation, release behavior and mobile parity. None of
those stories may broaden the v1 persona, intent set, context allowlist,
authority or interruption rules without a reviewed contract version.

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
