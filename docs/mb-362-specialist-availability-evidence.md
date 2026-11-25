# MB-362 online specialist availability evidence

## Delivered boundary

`consultation-service` owns publication, owner listing, and tombstone
withdrawal for exact 60-minute online specialist availability. OpenAPI 1.1.0,
ADR 0019, Liquibase change 003, the PostgreSQL dictionary, Spring provider,
and the Next.js consumer use the same `IN_APP_CHAT` / `IN_APP_VIDEO` model.
There is no practice-location entity, phone modality, external meeting link,
booking, or video-room authorization in this story.

The active-slot exclusion constraint prevents overlap for one specialist. A
pessimistic profile lock serializes publication commands before the overlap
check, while the unique owner/idempotency-key constraint preserves exact
replays. Withdrawal is owner-scoped, uses `If-Match`, increments the aggregate
version, and retains the row for audit.

## Automated coverage

- Provider HTTP integration: approval, role, owner isolation, exact duration,
  UTC and timezone validation, disabled video, idempotent replay, conflicting
  key reuse, overlap, stale/withdrawn/version conflicts, and list tombstones.
- Concurrent publication: two overlapping commands produce one `201` and one
  `409` against PostgreSQL.
- Enabled-video context: `IN_APP_VIDEO` publishes only when the typed feature
  flag is true, without returning a location or meeting link.
- Migration integration: table, check/unique/exclusion constraints, and active
  overlap behavior.
- OpenAPI contract: operation set, online-only modalities, and absence of
  location/link fields.

## Local verification run on 2026-09-19

| Command | Result |
| --- | --- |
| cached Maven `-DskipTests compile` | Pass |
| cached Maven `-Dtest=ConsultationOpenApiContractTests,ConsultationLiquibaseChangelogTests test` | Pass |
| `docker info` | Blocked: Docker Desktop Linux engine pipe was unavailable |
| full cached-Maven `test` | Blocked after 16 discovered tests: 0 failures, 12 context errors because Testcontainers could not find a valid Docker environment; required CI job must pass before merge |

No live user data or external provider is used. The frontend mobile browser
fixture is synthetic and is documented separately in its repository. GitHub
CI in a normal checkout is the final evidence for the PostgreSQL Testcontainers
suite and standard quality gate.
