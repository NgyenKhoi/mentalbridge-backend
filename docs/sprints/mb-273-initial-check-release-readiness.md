# MB-273 — initial-check release readiness

This runbook is the release record for Story 1105 and subtasks 1240–1243. A
result is valid only when its evidence class is stated. Fixture or service
evidence must never be reported as a live cross-stack run.

## Current release decision

**BLOCKED as of 2026-09-11.** The fixture-browser and service-integration
journeys pass. The live-cross-stack run is still required before MB-273 can
support a final release decision. No approved deployed environment or
synthetic live credentials were supplied. This is an evidence gap, not a
waiver.

| Evidence class | Environment | Result | What it proves |
| --- | --- | --- | --- |
| `fixture-browser` | Local Next.js production build plus controlled Identity, Care, and Content fixtures | PASS, 2026-09-11 | Browser/BFF orchestration, UX, recovery, ownership boundary, redaction assertions, and screenshots |
| `service-integration` | Local Next.js production build, real Care service, disposable PostgreSQL Testcontainer, controlled Identity and Content fixtures | PASS, 2026-09-11 | Real Care HTTP, persistence, migrations, deterministic routing, and browser/BFF integration |
| `live-cross-stack` | Approved deployed frontend/BFF and Care environment with two synthetic users | NOT RUN | Deployed topology, environment configuration, real controlled PostgreSQL, and result reopening |

Do not mark the Story or subtasks 1240–1243 complete while either required row
above remains blocked or not run. Any Critical or High failure is an automatic
no-go until fixed and re-tested.

## Gates executed on 2026-09-11

- Frontend `npm run quality`: PASS — format, lint, type generation/typecheck,
  Identity/Care/Content OpenAPI snapshots, realtime contracts, 39 unit-test
  files with 214 tests, and the Next.js production build.
- Frontend `npm audit --json`: PASS — zero current advisories across production
  and development dependencies at the time of the run.
- Frontend `npm run test:e2e:initial-check:fixture`: PASS — one Chromium
  release-readiness journey with desktop/mobile and sanitized JSON evidence.
  Its commit fields intentionally read `working-tree`; regenerate the evidence
  at the reviewed commits before final sign-off.
- Frontend full fixture browser regression: PASS — 23 tests passed and the
  explicit MB-273 mode-only test was correctly skipped in the generic run.
- Backend targeted no-database gate: PASS — `SupportEvaluationServiceTests`,
  `CareOpenApiContractTests`, `CareEventContractTests`, and
  `CareLiquibaseChangelogTests`.
- Backend test compilation: PASS.
- Backend full Care suite: PASS — 88 tests with clean PostgreSQL migrations,
  representative existing-database upgrade, contract, ownership, deterministic
  routing, persistence, and application integration coverage.
- Service-integration browser run: PASS — one Chromium journey using real Care
  and a disposable PostgreSQL Testcontainer.
- Live cross-stack run: NOT RUN — approved URL and two synthetic credential
  sets were not available.

## Synthetic data and safety rules

- Use only fresh synthetic accounts reserved for MB-273. Never use personal
  health data, production credentials, or a production environment.
- One primary user completes the journey. A second synthetic user creates the
  foreign GAD-7 evidence used for owner-isolation verification.
- The safety-positive fixture uses PHQ-9 total `1` with item 9 equal to `1` and
  GAD-7 total `0`. This proves safety precedence without recording raw answers
  in evidence artifacts.
- Store credentials only in the runner process environment. Do not paste them
  into commands, screenshots, logs, commits, Jira, or pull-request text.
- Playwright trace recording is disabled in live mode because browser traces
  could capture login payloads.

## Reproducible commands

Run from `mentalbridge-frontend/mentalbridge` after checking out the reviewed
frontend and backend commits.

```powershell
npm ci
npm run quality
npm run test:e2e:initial-check:fixture
```

For the real Care/disposable PostgreSQL run, start Docker Desktop and point to
the reviewed backend worktree:

```powershell
$env:CARE_BACKEND_DIRECTORY='D:\path\to\reviewed-backend'
npm run test:e2e:initial-check:service
```

For an explicitly approved deployed environment, inject the following values
through the CI secret store or an ephemeral shell. Do not record their values:

- `PLAYWRIGHT_BASE_URL`
- `MB273_SYNTHETIC_EMAIL` and `MB273_SYNTHETIC_PASSWORD`
- `MB273_FOREIGN_SYNTHETIC_EMAIL` and
  `MB273_FOREIGN_SYNTHETIC_PASSWORD`
- `MB273_ENVIRONMENT_ID`
- `MB273_FRONTEND_COMMIT` and `MB273_BACKEND_COMMIT`
- `MB273_LIVE_APPROVED=true`

Then run:

```powershell
npm run test:e2e:initial-check:live
```

The live runner rejects non-HTTPS remote URLs and URLs containing credentials.
It prints only the evidence class, environment identifier, and commit IDs.

## Scenario and evidence matrix

| Scenario | Fixture | Service | Live | Expected outcome |
| --- | --- | --- | --- | --- |
| Unauthenticated initial-check state | Automated | Automated | Automated | `401`, no protected state |
| Fresh user, missing profile, invalid empty name/future DOB | Automated | Automated | Automated | Profile-required state and accessible Vietnamese field errors |
| Profile, consent, PHQ-9, GAD-7, combined result | Automated | Automated | Automated | Separate saved evidence and deterministic result |
| Duplicate PHQ-9 submission | Automated | Automated | Automated | Idempotent `201`, no duplicate evidence |
| One-shot GAD-7 unavailable, retry, refresh/resume | Fixture fault | Test-only Care fault | Browser-injected BFF response fault | Safe unavailable state, then resume at GAD-7 |
| Optional Content unavailable | Fixture fault | Fixture fault | Browser-injected BFF response fault | Combined result and local safety guidance remain visible |
| Positive PHQ safety item with minimal bands | Automated | Automated | Automated | `SAFETY_FOLLOW_UP_RECOMMENDED` overrides band routing |
| Foreign GAD-7 evidence | Second fixture actor | Second persisted Care actor | Second synthetic live user | `404 ASSESSMENT_NOT_FOUND`; no ownership disclosure |
| Reload and reopen from dashboard | Automated | Automated | Automated | Exact saved PHQ/GAD/evaluation evidence reopens |
| Browser/log redaction | Automated assertions | Automated assertions | Automated assertions | No raw answers, tokens, or opaque journey IDs in JS storage/URL; journey cookies are HttpOnly |
| Corrected PHQ-9 definition and immutable historical version | Migration tests | PostgreSQL migration tests | Verify persisted version from new attempt | New attempt uses `phq9-vi-vn-capstone-v2`; v1 remains eligible historical policy data |

The two browser-injected live dependency failures prove frontend recovery in the
deployed UI; they do not prove that the deployed Care or Content service
actually failed. Record them exactly with that limitation.

## Evidence to attach

- Frontend and backend commit SHAs, environment ID, UTC timestamp, and exact
  commands.
- Fixture screenshots:
  `docs/evidence/mb-273-fixture-result-desktop.png` and
  `docs/evidence/mb-273-fixture-result-mobile.png`, plus sanitized
  `docs/evidence/mb-273-fixture-persisted-evidence.json`, in the frontend
  repository.
- Service-integration sanitized metadata:
  `docs/evidence/mb-273-service-persisted-evidence.json` in the frontend
  repository. Desktop/mobile screenshots are attached to its Playwright report
  and are not duplicated in Git because deterministic visual output matches the
  fixture images.
- Service/live Playwright report and the attached sanitized result JSON. The
  JSON contains identifiers, versions, scores, bands, safety, tier, reason, and
  policy metadata but no answers or tokens.
- Backend Maven report including OpenAPI/event contracts and both clean and
  representative-existing PostgreSQL migration tests.
- Redacted application logs. Search before attachment for bearer tokens,
  passwords, raw answer arrays, personal data, and synthetic secret values.

## Failure, rollback, and cleanup

- Stop release review on any Critical/High finding or mismatch in ownership,
  safety precedence, questionnaire version, persisted evidence, or redaction.
- Keep failing reports locally, but do not attach artifacts containing secrets
  or raw health answers. Remove or redact them first and re-run the search.
- Test-only fault controls exist only in the Care test application and local
  fixtures; they are not production endpoints.
- The service run owns a disposable PostgreSQL Testcontainer and removes it when
  the run ends. For live runs, delete the two synthetic accounts and their
  profile/consent/assessment/evaluation data using the environment's approved
  cleanup procedure; record the cleanup ticket or confirmation.
- Roll back a candidate deployment through the normal environment deployment
  mechanism. Database changes are forward-only; never edit or reverse already
  published questionnaire changelogs in place.

## Known limitations outside this Sprint

AI synthesis, Journal and mood history, longitudinal trends, the full reviewed
intervention catalogue, and notification automation remain deferred. The
result is screening support guidance, not a diagnosis, emergency response,
automatic booking, or automatic contact with another person.

## Final sign-off record

Complete this section only after both required non-fixture runs and all quality
gates pass:

- Environment ID:
- Frontend commit:
- Backend commit:
- Live run timestamp:
- Service-integration report:
- Live-cross-stack report:
- Unresolved Low findings and approved owner/date:
- Cleanup confirmation:
- Delivery owner decision: `GO` / `NO-GO`
