# MB-623 consultation journey verification

Canonical versioned map: [consultation-journey-v1](../policies/consultation-journey-state-map-v1.md).
This is local/CI verification, not deployment, real-money or enabled-video approval.

## Safe prerequisites

Use the paired frontend MB-623 branch with its locked npm dependencies and a
current production build. Docker must be running for PostgreSQL Testcontainers.
Do not copy a developer `.env` or use the shared dev/staging database. Run from
the backend root with `SPRINGDOTENV_ENABLED=false`. No provider credentials,
protected staging environment or tunnel are required. Only disposable synthetic
approved-profile/entitlement prerequisites are inserted; appointments, evidence,
summaries, ratings and settlement use real commands.

```powershell
$env:SPRINGDOTENV_ENABLED = 'false'
mvn.cmd -q -f consultation-service/pom.xml test '-Dtest=ConsultationJourneyIntegrationTests' '-Duser.timezone=UTC'
mvn.cmd -q -f care-service/pom.xml test '-Dtest=ConsultationBriefIntegrationTests' '-Duser.timezone=UTC'
```

From the frontend `mentalbridge/` directory, run `npm.cmd ci`,
`npm.cmd run quality` and install the project Playwright Chromium if necessary
with `npm.cmd run test:e2e:install`. The production browser journey is explicit:

```powershell
mvn.cmd -q -f consultation-service/pom.xml test '-Dtest=ConsultationJourneyIntegrationTests#productionBrowserBffAndRealConsultationPreserveTheWholeJourney' '-Dmb623.frontend=E:/mentalbridge-repo/mb623-frontend/mentalbridge' '-Dmb623.backend=E:/mentalbridge-repo/mb623-backend' '-Duser.timezone=UTC'
```

Replace the absolute checkout paths with your paired worktrees. Linux uses `mvn`
and `npm`; no business behavior differs. An installed local Chrome may be used
by setting `PLAYWRIGHT_BROWSER_CHANNEL=chrome`; the default is project Chromium.
The controlled runner accepts only loopback roots and an ephemeral fixture key
through stdin, never URL arguments or committed environment files. It cannot
target an arbitrary deployed stack. It uses a separate loopback Web port 3197,
random fixture/service ports and test-owned databases. It does not restart the
developer's app, change provider settings or create real users/payments.

The fixture clock advances to the existing booking/session/evidence boundaries;
it never rewrites an appointment schedule or inserts completion/ledger rows.
Production code still decides every settlement. Test evidence is non-content
metadata from a synthetic trusted producer, not proof of actual chat messages.

## Coverage

| Boundary | Expected evidence |
| --- | --- |
| Discovery / booking | Approved Specialist, exact slot, authoritative plan/cap, one HELD credit, replay same appointment |
| Decision / change | Assigned acceptance; rejection, deadline expiry, cancellation once; replacement rollback and cap-safe transfer |
| Session | Server-authorized waiting/send windows; timer-only end not completion; duplicate/conflicting evidence and technical review |
| Settlement | Both no-shows, one-sided no-shows, insufficient evidence, exactly-one consumption/earning, no earning from nonpayable outcomes |
| Continuation | Assigned completed-only summary, USER-private next-step state, separate reuse consent, one owner rating |
| Dispute | Participant open, earning blocked even after maturity, ADMIN version/idempotency, explicit adjustment without historical rewrite |
| Dependency / modality | Care outage stays unavailable; wrong owner denied; video explicitly disabled rather than simulated |

The production browser run retains only a sanitized manifest and synthetic
screenshots in frontend `test-results/mb623-consultation/`. Browser traces,
request bodies, cookies, JWTs, credentials, fixture keys and service payloads are
not retained. The manifest records source revision/dirty state, classification,
executed time and successful checks. An older manifest cannot prove a later head;
rerun after relevant code changes and check its time/revision.

## Delivery checkpoint

Local checkpoint, 2026-10-15 (Windows, UTC test time; disposable PostgreSQL):

| Command / boundary | Result |
| --- | --- |
| `mvn.cmd -q -f consultation-service/pom.xml verify -Duser.timezone=UTC` | 232 discovered, 229 passed, 3 explicit opt-in tests skipped; zero failures/errors |
| `ConsultationJourneyIntegrationTests` ordinary owner methods | 12 scenarios passed, including concurrent settlement and all nonpayable outcomes |
| Explicit production-browser method above | Executed successfully: six golden/exception journey checks; 1440/375px synthetic screenshots |
| `care-service`: `-Dtest=ConsultationBriefIntegrationTests` | 9 passed, including sharing/revocation/assignment/window/dependency boundaries |
| `care-service`: `-Dtest=SupportPlanIntegrationTests#userReviewsRejectsOrAtomicallyAcceptsAnExactSpecialistProposal` | 1 passed; Care owns proposal review/atomic acceptance |
| Frontend `npm.cmd run quality` non-build stages | Format, lint, TypeScript, public/Realtime contract checks; 237 files / 1,339 unit tests passed |
| Frontend runner guard test | 4 passed: malformed/external/credential/query bootstrap rejected without echoing input |
| Frontend production build | Default Turbopack build passed in Linux Docker; Windows official `next build --webpack` plus standalone preparation passed |

The first full Consultation run exposed a pre-existing profile audit test that
ordered same-instant events by random UUID. A test-only monotonic clock now
preserves the chronological assertion; all six profile tests and the subsequent
complete module verification passed. No production clock or policy changed.
The first Windows `npm run quality` build failed resolving
`@vercel/turbopack-next/internal/font/google/font` (`next/font/google` import-map
error). The default Linux Docker build and official Windows Webpack alternative
both passed without changing dependencies, application code or CI gates. This
does not relabel the failed Windows default-build attempt as success.

Exact final revisions, rerun manifest and paired PR/CI links are recorded at
handoff; the committed documentation does not self-attest a future commit.
The ordinary
backend gate runs the twelve owner scenarios without starting browsers. The
explicit browser method is opt-in and is not reported as passing when skipped.
Frontend dev CI remains non-browser; mobile native jobs are unchanged.

Known limits: Identity authentication, live Realtime transport, real Care plus
Consultation in one browser stack, enabled LiveKit and real payment/payout are
not proved by the controlled browser run. Their owner suites and separate
approved-provider gates remain distinct. Video must stay capability-disabled
where MB-384's provider/configuration gate is not satisfied. Existing video
pending verification has no approved automatic release or 24-hour fallback.
