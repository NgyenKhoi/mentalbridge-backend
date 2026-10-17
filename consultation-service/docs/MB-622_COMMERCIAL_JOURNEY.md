# MB-622: canonical commercial journey

Consultation owns the existing payment, subscription, credit, completion,
earning and payout records. No second ledger or cross-service table access is
introduced. USER, SPECIALIST and ADMIN consumers read these canonical facts.

## Approved sandbox policy

The project owner approved Plus 5,000 VND and Premium 10,000 VND. New immutable
`sandbox-vnd-2026-10` plan versions use `sandbox-full-price-v1`: a Plus upgrade
charges the full Premium price with zero offset, revokes only upgrade-held
unused Plus credits after verified success, and grants ten Premium credits.
An appointment-held Plus credit keeps its original allocation and plan version.
Allocation 300,000 VND and earning 210,000 VND are unfunded synthetic demo facts,
not an economically funded commercial product. Such earnings cannot use a real
payout adapter. Historical plan prices, ordinary offset calculations and settled
financial snapshots are not rewritten. See ADR 0032's owner-approved amendment.

## Rollout and failure boundaries

Apply append-only migrations 021 and 022, with their canonical model and field
dictionary. First roll out the nullable checkout-URL consumer; then drain old
billing writers and deploy the new writer. Null means checkout creation is not
confirmed, never a fabricated redirect. A reservation commits before the provider
call, without a database transaction around network I/O. Replaying its command key
returns the same persisted record and never automatically resends a possible
charge. Callback success, callback failure and expiry remain authoritative.

Do not roll back by deleting ledger facts or restoring retired prices in place.
Use a reviewed forward migration and reconcile pending requests before any
writer rollback. Real checkout and payout remain disabled by default; this story
does not change credentials, approval flags or deployment configuration.

OPEN disputes and released credits block earning materialization or withdrawal.
Payout preparation locks source appointments before checking their completion,
credit and dispute facts. Hold elapsed is necessary but not sufficient. Retry
uses current eligibility and latest-attempt reconciliation without duplicating
the earning count or changing immutable allocation snapshots.

## Reproduce the controlled cross-stack proof

Prerequisites: Java 21, Node, Docker, installed frontend dependencies and the
matching Playwright Chromium. Both checkout and payout are FAKE. No wallet,
shared database or externally hosted service is used.

From `mentalbridge-frontend/mentalbridge`:

```powershell
npm.cmd run build
npx.cmd playwright install chromium
```

From `mentalbridge-backend/consultation-service`:

```powershell
$env:SPRINGDOTENV_ENABLED='false'
.\mvnw.cmd -q '-DargLine=-Duser.timezone=UTC' '-Dmb622.frontend=E:/mentalbridge-repo/mentalbridge-frontend/mentalbridge' '-Dtest=CommercialCrossStackTests' test
```

Set the frontend path to the actual absolute checkout location. Without this
explicit property the cross-stack test is skipped, not claimed as CI E2E proof.
Ordinary repository CI still runs its provider, migration and financial tests.

The harness starts a fresh PostgreSQL Testcontainer and the real Spring HTTP
server, then a production Next standalone server and Chromium. Its protected,
loopback-only Identity fixture uses ephemeral signed test credentials passed
only through child stdin. It seeds a synthetic approved specialist, not financial
records. Payment, subscription, credit, appointment, completion, earning and payout
facts are created by the actual application paths. Complete signed MoMo-shaped
callbacks are delivered twice; no actual MoMo API is called. A test-only clock
advances completion grace and seven-day settlement hold without real waiting.

The journey covers purchase -> pending reload -> verified activation -> booking
and acceptance -> full-price upgrade -> evidence-backed completion (timer alone
does not earn) -> one earning on the original Plus snapshot -> hold -> FAKE payout
and replay -> specialist reload -> ADMIN canonical reconciliation. Amounts,
source IDs, completion identity and counts are checked against the same database.

Sanitized outputs are in frontend `test-results/mb622/`: a JSON manifest with both
source SHAs/dirty flags, synthetic trace IDs, provider references and viewport
screenshots. No credentials, raw callback or chat content is retained as evidence.
The PR records the final clean-head execution; a dirty-worktree run is provisional
only. Full local debug logs are not publication artifacts. Real MoMo onboarding,
money transfer, production readiness and clinical evidence are outside this proof.
