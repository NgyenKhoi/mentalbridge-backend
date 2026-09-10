# MB-236 private Journal CRUD delivery evidence

## Delivered scope

| Work item | Runtime evidence |
| --- | --- |
| MB-241 | Journal OpenAPI request/response shapes align with strict MongoDB documents, cursor pagination, headers, errors, and tombstones. |
| MB-242 | AES-256-GCM encrypted revisions, keyed hashes, bounded persisted idempotency, exact retry handling, and atomic revision compare-and-swap. |
| MB-243 | Typed same-origin Next.js BFF and server-only provider client with bounded fail-closed response validation. |
| MB-244 | Accessible responsive create/list/detail/revise/conflict/delete UI with explicit empty, loading, malformed, unavailable, unauthorized, not-found, and unknown-outcome states. |
| MB-245 | Real MongoDB HTTP verification plus controlled Chromium desktop/mobile journeys and committed screenshots. |

## Verification record

All journal identities and content used below are synthetic. The Atlas credential and encryption keys remain in ignored local environment files and are absent from logs, screenshots, evidence, and Git.

| Gate | Result |
| --- | --- |
| `npm run test:integration` | Passed against a newly created `mb236_verify_<timestamp>` Atlas database. The test applied migrations, exercised HTTP create/replay/read/concurrent revise/delete, verified owner isolation and confirmed plaintext was absent from the raw BSON document. The disposable database was dropped in `finally`. |
| Concurrent same-revision writes | Exactly one `200`; the loser returned stable `412`. |
| Exact create retry | Returned the same entry ID and left exactly one MongoDB document. |
| Backend lint/typecheck/OpenAPI/migration/unit gates | Passed; 15 unit/HTTP tests passed before the final real-Mongo BSON-boundary verification. |
| Frontend contract/typecheck/lint/unit/build gates | Passed; 196 unit tests and the production build passed in the same delivery. |
| Journal Chromium desktop journey | Passed create, refresh persistence, detail, conflict with retained draft, exact retry, revise, tombstone copy, and delete. |
| Journal Chromium mobile journey | Passed at 390x844 with viewport set before navigation; detail and revise actions remained accessible. |

## Evidence

- Desktop: `mentalbridge-frontend/mentalbridge/docs/evidence/mb-236-journal-desktop.png`
- Mobile: `mentalbridge-frontend/mentalbridge/docs/evidence/mb-236-journal-mobile.png`
- Provider regression: `journal-ai-service/src/journals/journal.mongo.integration.test.ts`
- Browser regression: `mentalbridge-frontend/mentalbridge/tests/e2e/journal.spec.ts`

AI/inferred mood or tags, reflection generation, trends, analytics, and immediate physical erasure remain outside MB-236.
