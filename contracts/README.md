# Contract source of truth

All language-neutral integration contracts live under this directory. Service-local contract copies are not allowed because they drift from consumers and review tooling.

| Contract | Canonical location |
| --- | --- |
| REST/OpenAPI | `contracts/openapi/` |
| Kafka commands and events | `contracts/events/<owner>/` |
| Client WebSocket envelopes | `contracts/websocket/` |
| Unaccepted future proposals | `contracts/proposals/` |

## OpenAPI lifecycle

Every OpenAPI path item declares `x-mentalbridge-status`:

- `implemented`: the owning service has a production handler and tests for the documented method, authorization, success response, and stable errors;
- `planned`: the shape is design input only and must not be advertised as an available runtime endpoint.

New future APIs should start in `contracts/proposals/`. Existing forward-looking paths may remain in an OpenAPI file only when explicitly marked `planned`. Promoting a path to `implemented` is an atomic change containing the handler, boundary tests, and contract compatibility check.

Framework DTOs, controllers, generated types, database entities, and provider payloads are never the cross-service source of truth.

`care-service-v1.yaml` remains the unchanged v1 source for coarse historical
SupportEvaluation behavior. `care-support-evaluation-v2.yaml` is the additive
source for `/api/v2/support-evaluations`; consumers select the API version by
path and must not interpret a v1 support tier as a v2 domain contribution.
`care-support-guide-v1.yaml` is the additive implemented owner contract for the
all-tier one-time guide at `/api/v1/support-guides`; it is not a SupportPlan
lifecycle contract. `content-notification-service.yaml` supplies the exact
eligible resource's immutable reviewed display snapshot alongside publication
provenance so Care can persist stable history.

## Required paired changes

- REST route behavior changes together with its OpenAPI contract and provider contract tests.
- Kafka/WebSocket payload behavior changes together with the versioned schema and producer/consumer tests.
- A PostgreSQL migration changes together with the field data dictionary and mapping/integration tests.
- Runtime configuration changes together with `.env.example`, the module README, and configuration tests.

Run `pwsh ./scripts/verify-repository.ps1` before opening a pull request. CI applies the same repository and changed-file policy checks.
