# Contract source of truth

All language-neutral integration contracts live under this directory. Service-local contract copies are not allowed because they drift from consumers and review tooling.

| Contract | Canonical location |
| --- | --- |
| REST/OpenAPI | `contracts/openapi/` |
| Kafka commands and events | `contracts/events/<owner>/` |
| Client WebSocket envelopes | `contracts/websocket/` |
| Unaccepted future proposals | `contracts/proposals/` |

## OpenAPI lifecycle

MB-684's accepted [Community chat baseline](proposals/community-chat-v1.openapi.json)
remains frozen in proposals with its original planned markers. Its closed
[commands](proposals/community-chat-command-v1.schema.json),
[socket events](proposals/community-chat-realtime-v1.schema.json) and
[durable facts](proposals/community-chat-fact-v1.schema.json) define the accepted target.
MB-685 registers only the delivered direct-provider subset and promotes the
unchanged fact shape under `events/community/`. MB-687 adds nine private-group
lifecycle operations and exact-message report intake. MB-689 promotes signed
internal projections and isolated socket delivery; MB-690 adds presence-only
foreground heartbeat, presence events and current authorized peer projections.
MB-691 adds scoped chat notification authorization, the comment-reaction provider
fragment and separate `community-comment-reaction-v1.schema.json` fact. Existing
interaction-v1 producers and schemas remain compatible. The new generic
`community-push-v1.schema.json` defines a payload only; actual PUSH provider/device
delivery remains MB-675/679. Typing/shared receipts and ADMIN case operations
remain future runtime work.
See [ADR 0042](../docs/adr/0042-community-chat-contract.md).
Existing appointment and Community interaction schemas are unchanged.

Every OpenAPI path item declares `x-mentalbridge-status` when all methods share one state. A mixed path declares it on each operation instead:

- `implemented`: the owning service has a production handler and tests for the documented method, authorization, success response, and stable errors;
- `planned`: the shape is design input only and must not be advertised as an available runtime endpoint.

New future APIs should start in `contracts/proposals/`. Existing forward-looking paths may remain in an OpenAPI file only when explicitly marked `planned`. Promoting a path to `implemented` is an atomic change containing the handler, boundary tests, and contract compatibility check.

Framework DTOs, controllers, generated types, database entities, and provider payloads are never the cross-service source of truth.

`openapi/identity-service-v1.yaml` exposes the implemented MB-587 ADMIN-only administration-audit browse and CSV export operations. Both use the same bounded filters, ordering, 365-day read-retention boundary, and privacy-minimized projection fields. Cross-service sources ingest through the versioned `events/identity/administration-audit-event-v1.schema.json` fact into Identity's minimized projection; the query API never reads another owner's database.

`events/identity/administration-audit-event-v1.schema.json` is the implemented MB-587
fact for cross-service administration audit events ingested from Consultation, Content,
Community, and Identity. It carries only bounded operational metadata (sourceService, domain,
actor, action, result, safe reasonCode, correlationId, safe target/tombstone, occurredAt).
Raw payloads, journal text, assessment answers, chat body, credentials, tokens, and provider
payloads are strictly excluded by schema and consumer allowlisting.

`events/identity/account-state-changed-v1.schema.json` is the implemented MB-365
fact emitted atomically when Identity suspends or restores a non-admin account.
It carries only the account identifier, current status, immutable actor role,
closed reason code, correlation metadata, and aggregate version. Repeated
same-state commands emit no duplicate fact.

MB-565 adds the implemented `events/care/user-notification-fact-v1.schema.json`
and `events/identity/user-notification-fact-v1.schema.json` contracts. Care
publishes only authenticated assessment-result and newly persisted SupportGuide
availability facts on `mentalbridge.care.user-notification.v1`; Identity
publishes only USER activation/restoration facts on its existing account
lifecycle topic. Both contracts are closed, identity-bound, and exclude
assessment answers/scores, safety flags, resource copy, email addresses, and
provider payloads. Content/Notification owns rendering, preference enforcement,
durable delivery state, and logical replay deduplication.

For these two MB-565 v1 facts, producers truncate source instants to UTC
microseconds before writing both JSON `payload.availableAt` and the outbox
`occurred_at`. Truncation never rounds into the next microsecond or second.
The relay emits the same ISO-8601 text (at most six fractional digits) after
PostgreSQL reload. Content requires exact timestamp equality in addition to
the existing kind, recipient, aggregate identity and version bindings.

`care-service-v1.yaml` remains the unchanged v1 source for coarse historical
SupportEvaluation behavior. `care-support-evaluation-v2.yaml` is the additive
source for `/api/v2/support-evaluations`; consumers select the API version by
path and must not interpret a v1 support tier as a v2 domain contribution.
MB-667 adds the separate read-only v1
`/support-evaluations/{supportEvaluationId}/combined-meaning` subresource so
older strict clients keep receiving their original evaluation shape. Its exact
pair and source policy remain separate from the versioned draft presentation;
the owner-supplied Stitch prototype is available, while twenty neutral copy
variants remain pending Care/content-owner review and are not approved for
deployment. It adds no combined score, new routing policy or v2 top-level tier.
`care-support-guide-v1.yaml` is the additive implemented owner contract for the
all-tier one-time guide at `/api/v1/support-guides`; it is not a SupportPlan
lifecycle contract. MB-668 adds its episode-scoped ensure operation
`/api/v1/screening-episodes/{episodeId}/support-guide` (1.1.0) and the
read-only `/api/v1/guided-journey/current` pointer in `care-service-v1.yaml`
(1.6.0); clients send no assessment identifiers to either. `content-notification-service.yaml` supplies the exact
eligible resource's immutable reviewed display snapshot alongside publication
provenance so Care can persist stable history.
`care-service-v1.yaml` also exposes the implemented MB-372/MB-373/MB-513/MB-376
SupportPlan surface. It accepts only an owned SupportEvaluation v2 reference,
returns persisted Care-owned plan snapshots, supports admitted choices and
explicit activation/lifecycle commands, and exposes owner-only bounded activity
occurrences with local-time and exact source provenance. Owner engagement uses
versioned replacement/deletion; its minimized event excludes private reflection
and does not create a specialist observation feed. The contract never accepts
arbitrary resources, a client package, an adherence score, a recovery score, or
AI-selected state.
The same contract exposes MB-559 explicit reassessment self-report
create/current/replace/delete operations plus Reassessment Summary composition
and owner-only current/detail/history reads. `reassessment-summary-v2` keeps
local PHQ-9/GAD-7 trends, minimized Journal/AI evidence, SupportPlan engagement,
and explicit self-reported experience separate. Occurrence
helpfulness/reflection is separately labelled supporting activity evidence and
never substitutes for the self-report. Historical `reassessment-summary-v1`
snapshots remain readable without rewrite. Neither version has a combined score
or overall direction.

The same Care contract exposes MB-638 `GET/PUT
/api/v2/support-plan-personalization`. It returns a revision-zero deterministic
fallback for an existing user without saved input, persists only a bounded
non-diagnostic vocabulary, and uses strong ETag replacement. `PlanFocus`
includes optional exact personalization provenance; updating preferences
returns a review-required reference instead of silently mutating an active plan.

## Required paired changes

- REST route behavior changes together with its OpenAPI contract and provider contract tests.
- Kafka/WebSocket payload behavior changes together with the versioned schema and producer/consumer tests.
- A PostgreSQL migration changes together with the field data dictionary and mapping/integration tests.
- Runtime configuration changes together with `.env.example`, the module README, and configuration tests.

Run `pwsh ./scripts/verify-repository.ps1` before opening a pull request. CI applies the same repository and changed-file policy checks.

## MB-685 active direct-chat subset

[`openapi/community-chat-v1.json`](openapi/community-chat-v1.json) is the runtime provider contract for delivered direct/group chat and report-intake operations. The accepted MB-684 artifact in proposals remains frozen for downstream transport and case administration. Only delivered paths are registered; schemas remain compatible with that baseline. A schema variant alone never advertises an unregistered socket/internal/ADMIN capability.
