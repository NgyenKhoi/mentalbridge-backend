# MB-377 service-plan consultation credits evidence

## Delivered

- ADR 0020 freezes `FREE=0`, `PLUS=1`, `PREMIUM=3`, upgrade-only allocation, owner-only balance, and explicit `DEMO`/`PAID` provenance.
- `GET /api/v1/service-credits` provisions the current effective period idempotently and returns current counts plus a bounded append-only history.
- PostgreSQL migration 004 adds constrained credit periods, indivisible credit rows, and account-scoped idempotent transition facts.
- Owner transitions support hold, release, consume, and forfeit; exact replay is a no-op and conflicting key reuse fails closed.
- The companion frontend uses an authenticated same-origin BFF and does not derive balance from package names.

## Verification on 2026-09-20

- `mvn -q -DskipTests compile` — pass.
- `mvn -q -Dtest=ConsultationOpenApiContractTests,ConsultationLiquibaseChangelogTests test` — pass.
- Full `mvn test` — environment-blocked: Docker Desktop was unavailable, so Testcontainers could not start PostgreSQL. The only non-Docker assertion initially found was the expected migration-list update; after correction, the focused contract/changelog command above passed.
- Frontend `npm run typecheck` — pass.
- Frontend focused Vitest — 3 files, 12 tests passed.
- Frontend lint is recorded by the companion PR.

No live payment, paid subscription, browser-to-real-service, or production evidence is claimed. Test data uses synthetic UUIDs and controlled-demo references.
