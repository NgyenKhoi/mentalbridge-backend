# MB-205 descriptive assessment progress delivery evidence

## Delivered scope

| Work item | Runtime evidence |
| --- | --- |
| MB-205 / 702 | Additive `care-service-v1` `GET /api/v1/assessments/{assessmentId}/progress`, RFC 9457 errors, generated frontend types, additive unknown-field handling and safe unknown-enum fallback |
| MB-205 / 703 | Cohesive `com.mentalbridge.care.progress` controller/service/repository, owner-scoped deterministic PostgreSQL selection, arithmetic delta/direction/band/duration and no side effects |
| MB-205 / 704 | Same-origin Next Care BFF, server-only credential forwarding, bounded parser/client and separately tested accessible progress panel |
| MB-205 / 705 | Production Next build exercised against Care Spring Boot and disposable PostgreSQL with deterministic synthetic identities/data and controlled failure injection |
| MB-205 / 706 | Sprint status, Review 1 closure matrix, requirements traceability, Care docs, frontend guide and synthetic screenshots aligned |

## Contract and persistence impact

- Contract: existing `care-service-v1` gains one backward-compatible authenticated GET operation and three additive response schemas. Successful fields are required/non-null; insufficient comparison is `409 INSUFFICIENT_COMPARABLE_DATA`; missing and cross-owner selection share `404 ASSESSMENT_NOT_FOUND`.
- Privacy: progress contains no raw answers, safety interpretation, consent evidence, profile field or upstream credential. Unknown additive fields are ignored after forbidden sensitive names are rejected; unknown enums fail to the explicit malformed/unavailable UI state.
- Persistence: no schema or field change. The existing partial index `ix_assessment_submission_user_history (user_id, submitted_at DESC, id DESC)` supports the owner/time/UUID ordering, so no Liquibase migration or field-dictionary update is required.

## Verification record

Environment: Windows, Java 23 running Java 21 release compilation, Spring Boot 4.1.0, Docker Desktop 28.4.0, Testcontainers 2.0.5, PostgreSQL 18.6 disposable container, Node 22.16.0, Next.js 16.3.0 and Chromium. All identities, consent rows, assessments and screenshots are synthetic. No webhook, cloud account, public tunnel, production credential or external API is used.

| Gate | Result |
| --- | --- |
| Focused Care tests | 13 PostgreSQL integration/arithmetic tests passed, then 3/3 contract tests passed after the final forward-compatibility assertion; all affected production/test sources compiled |
| Earlier same-delivery Care baseline `mvnw test` | 41 passed; not redundantly rerun after the focused MB-205 package/harness verification |
| `npm run contracts:sync` and `npm run contracts:check` | Care snapshot/types regenerated; Identity and Care drift checks passed |
| `npm run format:check`, `npm run lint`, `npm run typecheck` | Passed |
| Focused Vitest progress/BFF/history/parser suite | 19 passed; subsequent canonical-UUID regression 9 passed |
| Earlier same-delivery frontend unit baseline | 114 passed; affected suites were rerun after final changes |
| `npm run build` | Production build passed with the progress Route Handler present |
| `npx playwright test tests/e2e/care-assessment.spec.ts --workers=1` | 2 passed in 51.5 seconds with no skipped case |

The browser matrix covers first-assessment insufficient data, a compatible server-owned comparison, incompatible scoring version, voided evidence, forged/cross-user non-enumeration, timeout, Care 503, malformed response, result/history visibility during progress failure, anonymous exclusion, HttpOnly credentials and exact-result reopening.

## Evidence and remaining blockers

- Desktop: `mentalbridge-frontend/mentalbridge/docs/evidence/mb-205-progress-desktop.png`
- Mobile: `mentalbridge-frontend/mentalbridge/docs/evidence/mb-205-progress-mobile.png`
- Pull requests: recorded here after the repository branches are published.
- GAD-7 publication, personalized support actions, specialist handoff/sharing, automatic reminders/contact, AI personalization and real-user production deployment remain unavailable, unpublished, blocked or deferred under their existing gates.
