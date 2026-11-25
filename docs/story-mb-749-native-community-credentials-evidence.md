# MB-749: Native Community credential prerequisite

Story: [MB-749](https://vunguyenkhoi47.atlassian.net/browse/MB-749).
Owner: Thảo Nguyên (`Thaonguyen0507`). It blocks MB-692 mobile realtime
integration and remains a backend-only enabling outcome.

## Accepted decisions and delivery slices

On 2026-10-21 the owner selected a separate native credential prerequisite,
implemented before MB-692 realtime integration, and a Community-screen entry
for the later mobile journey. [ADR 0043](adr/0043-native-community-credential-exchange.md)
amends only native ticket ingress; Community ownership, BFF issuance and
appointment chat remain unchanged.

The first commit defines the planned additive OpenAPI and ADR with executable
contract validation. The provider commit promotes only that operation and adds
Identity-authenticated issuance using the same Redis ticket/consume lifecycle.
No new secret, runtime dependency, Kafka fact, durable model or migration is needed.

## Acceptance states and evidence

| State | Observable result | Must not expose | Authored test evidence |
| --- | --- | --- | --- |
| Active USER | Closed no-store `/community` credential; verified subject/expiry only | Identity bearer or private signer | `community-native-credentials.integration.test.ts` |
| Invalid/expired/BFF bearer on native route | 401 | A ticket or trusted BFF fallback | Signed HTTP tests |
| SPECIALIST/ADMIN | 403 before owner query | Peer access from role alone | Signed HTTP tests |
| Actor/role/expiry body, array or unparsed nonempty text | 400 | Client-selected actor | Closed-request tests |
| Current owner denies/revokes | 404 at issue; consume fails after revocation | Cached permission grant | Real Redis plus signed Community owner fixture |
| Owner/Redis unavailable | Safe 503; no ticket fallback | Dependency payload/token | Failure/recovery tests |
| Two consumes of one ticket | Exactly one succeeds | Second use or cross-namespace ticket | Real Redis concurrency and appointment isolation |
| Identity expires during owner read | Issue denied | Extended session | Time-bounded owner query test |
| Native HTTP → another replica's Socket.IO | Authorized subscribe and exact live message; replay rejected | Different namespace/actor or stale grant | `community.integration.test.ts` two-node Kafka/Redis/Socket.IO fixture |

## Verification

Base: backend `dev` `f674b923`. Windows, Node 24.19.0, disposable real Redis,
Kafka and MongoDB for applicable integration suites. Community REST is a signed
HTTP fixture; this is not a real mobile/Java cross-stack or Android execution claim.

| Command from realtime-service | Result |
| --- | --- |
| `node node_modules/vitest/vitest.mjs run --configLoader bundle --maxWorkers 2` | 122 passed / 13 files |
| `node node_modules/vitest/vitest.mjs run --config vitest.integration.config.ts --configLoader bundle --maxWorkers 1` | 48 passed / 4 files; includes eight new native HTTP/Redis tests and one native two-replica socket journey |
| `node node_modules/typescript/bin/tsc --noEmit` | Exit 0 |
| `node node_modules/typescript/bin/tsc -p tsconfig.build.json` | Exit 0 |
| `node node_modules/eslint/bin/eslint.js .` | Exit 0 |
| `node node_modules/prettier/bin/prettier.cjs --check .` | Exit 0 |
| `node scripts/validate-contracts.mjs` | Exit 0 |
| `node scripts/validate-migrations.mjs` | Exit 0; existing Mongo baseline unchanged |

Root repository/paired-change policy and `git diff --check` passed. The first
container attempt could not access Docker from the sandbox; rerunning with
Docker access passed, without replacing Redis with mocks. Initial lint found
test fixture typing/cleanup issues; corrected before the passing gates. Vitest 4
rejects the older `--minWorkers` flag; the supported command above passed.
The Kafka restart/failure tests emit safe transport warnings and upstream Node
timeout warnings while assertions pass. No warning was suppressed.

## Mobile and operations handoff

Active contract: [community-realtime-v1](../contracts/openapi/community-realtime-v1.json),
also referenced by the canonical Realtime OpenAPI. MB-692 calls
`POST /api/v1/community/socket-credentials` through its single configured edge
using the shared bearer injection seam, no body or `{}`, no automatic POST retry.
Each explicit connection/reconnection obtains a fresh in-memory ticket and uses
the existing `/community` handshake. REST history remains authoritative.

The edge needs an exact route to Realtime before any broad Community API prefix
rule. No deployed edge config is owned here; rollout requires that operator
mapping. Existing `REALTIME_COMMUNITY_ENABLED` controls registration. No new
environment variable is introduced. Roll back the native consumer first and
keep the trusted BFF and appointment operations intact.

PR/CI evidence must identify the published head before merge. Neither backend
tests nor source Maestro flows establish MB-692 `ui-ready`; native Android
execution remains the MB-666 milestone. No shared data or developer `.env` was used.
