# MB-649 SupportPlan Companion orchestration evidence

## Delivered boundary

Journal/AI exposes `POST /internal/v1/support-plan-companion/messages` for one
explicit user request. Care authenticates with a dedicated service credential
and forwards the verified end-user bearer plus one exact
`support-plan-companion-intent-v1` envelope. A USER bearer alone is rejected
before validation, consent or provider access. The endpoint returns either
guarded provider phrasing or `support-plan-companion-template-v1` copy. It
creates no conversation, history, plan action, schedule, review, consent,
booking or other domain mutation.

The callable request reuses the accepted MB-647 intent and context schemas
without adding fields. The response is
`support-plan-companion-message-v1`; it contains bounded text, generated versus
template delivery, optional navigation copied from validated context, contract
versions, provider/model provenance and a closed fallback reason. It contains
no command or mutation field.

## Contract and policy coverage

| Accepted rule | Implementation evidence |
| --- | --- |
| Deterministic intent before provider use | Strict Zod validation binds each Today/Plan/Progress/Adjust surface to its admitted intents and each intent to one exact context kind before consent or provider access. |
| Minimized allowlist | Seven closed context variants accept only ADR 0040 fields. Unknown identity, Journal, chat, emotion, assessment, consultation, provider and arbitrary page fields fail with `400`. |
| Text-only provider authority | The structured provider schema contains only `text`. Navigation is copied from deterministic validated context after generation; extra command fields make the provider result malformed. |
| Bounded tone and output | `support-plan-companion-prompt-v1` requests at most three short Vietnamese sentences. Runtime validates every generated and template response at 480 characters, omits an optional rationale rather than truncating facts when the composed template would exceed the limit, requires the ordered completed/available progress relation, rejects invented numeric facts or wrong partial-evidence meaning, and blocks markup/URL/authority/clinical claims. |
| Deterministic fallback | All seven intents have the exact policy-v1 Vietnamese templates. Missing/unavailable consent, disabled provider, timeout, quota, provider outage, malformed output and policy rejection return template copy. |
| Existing provider infrastructure | Gemini, OpenAI and Bedrock use the existing configured `FREE_PLUS` route and shared timeout. No automatic cross-provider fallback is added. Bedrock warm-up includes the new structured-output schema. |
| Ephemeral retention | The request, model-facing context and response are not persisted and do not enter SupportPlan history or the existing conversation store. No migration is required. |
| Safe observability | One completion event records correlation ID, intent, surface, contract/policy/prompt/template versions, provider/model, outcome, latency and fallback reason. Tests assert context values and generated text are absent. |

## Privacy and security review

- Authentication requires both a dedicated Care service credential and an
  Identity-issued bearer whose principal includes `USER`; current
  `AI_PROCESSING` consent is checked before a real provider call.
- Missing or incorrect Care credentials fail before request validation, consent
  lookup and provider access. The `/internal` path name is not treated as a
  security control.
- Consent denial or dependency uncertainty keeps the provider untouched and
  returns local copy from the already validated minimized context.
- The model never receives account/plan/action identifiers, bearer tokens, raw
  Journal/chat/community text, emotion context, assessment answers/scores,
  consultation content, credentials or arbitrary browser state.
- Untrusted titles, rationales and labels are JSON data with escaped delimiter
  characters. Prompt-injection output that adds diagnosis or authority claims
  is rejected and replaced by deterministic copy.
- No request/response/provider payload or generated text is logged. Existing
  request logging continues to redact authorization and cookie headers.
- V1 adds no persisted contextual-message quota ledger. Care owns explicit
  request admission and pending-request deduplication. The configured provider
  route and timeout remain authoritative; provider rate/quota rejection returns
  deterministic copy without alternate-provider retry.

## Controlled deterministic demonstration

Automated tests exercise generated phrasing for three distinct intents:

1. `EXPLAIN_ACTION` with one Care-approved Journal action;
2. `SUMMARIZE_PROGRESS` with exact partial factual counts; and
3. `EXPLAIN_REVIEW_OPTION` with four separate evidence dimensions and two
   admitted options.

The same suite switches to deterministic mode and proves template delivery for
all seven intents. Separate cases cover malicious title injection, provider
command fields, malformed output, timeout, quota, outage, consent denial,
consent dependency failure, wrong surface/context pair and raw sensitive
context fields. Boundary cases also prove 480-character generated/template
responses, inverted and invented progress counts, incorrect partial-evidence
claims, and rejection of USER-only or wrong-service-token requests.

## Verification

Run from `journal-ai-service/`:

| Command | Coverage |
| --- | --- |
| `npm run contract:check` | Passed: OpenAPI 3.1 resolution, implemented route/status inventory, closed versioned mutation-free response |
| `npm run build && node --test dist/support-plan-companion/support-plan-companion.test.js dist/llm-providers/llm-providers.test.js dist/app.test.js` | Passed: policy/orchestration/provider/HTTP focused suite |
| `npm test` | Passed: 139 Journal/AI unit, application and provider tests |
| `npm run format:check && npm run lint && npm run typecheck && npm run migration:check && npm run contract:check` | Passed: required static, migration and contract gates |
| `node --test dist/journals/journal.mongo.integration.test.js dist/analysis/analysis.mongo.integration.test.js dist/longitudinal-analysis/longitudinal-analysis.mongo.integration.test.js dist/emotion-check-ins/emotion-check-in.mongo.integration.test.js dist/notification-activity/notification-activity.mongo.integration.test.js dist/companion-chat/companion-chat.mongo.integration.test.js dist/benchmarks/benchmark.mongo.integration.test.js` | Passed: 7 isolated migrated-Mongo integration tests |

Mongo integration behavior remains unchanged because MB-649 adds no
persistence, collection, index or migration.

## Deferred client work

MB-650 owns contextual action/card triggers and anchored copy. MB-651 owns the
expanded conversation surface. MB-652 owns review-option presentation. MB-653
owns release hardening, interruption/frequency and any later preference
contract. MB-654 must consume the same versions and fallback meaning on mobile.
