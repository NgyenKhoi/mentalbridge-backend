# MB-384: local LiveKit video consultation

Owner: Consultation. Decision: [ADR 0039](../adr/0039-livekit-cloud-video-consultation.md).
Public authority: [Consultation OpenAPI v1](../../contracts/openapi/consultation-service-v1.yaml).
This runbook is not deployment, production or paid-provider approval.

## Configure the existing project

Use the existing `mentalbridge` Free project in the LiveKit Cloud dashboard.
An operator manages its API keys there; MB-384 does not create a project or
regenerate valid credentials. Inject `LIVEKIT_URL`, `LIVEKIT_API_KEY` and
`LIVEKIT_API_SECRET` into the backend process only. Do not send their values in
chat, commit them or copy them into Next.js/Expo environment variables.

Local Spring uses the repository's `springboot4-dotenv` property source when
started from the backend root; it does not assume the shell automatically loads
dotenv. The root untracked `.env` may hold local values. Real process variables
override them. CI/production use `SPRINGDOTENV_ENABLED=false` and injected
configuration. See [environment configuration](../agent-guides/environment-configuration.md).

Enable `CONSULTATION_VIDEO_ENABLED=true` for connection issuance, and separately
`CONSULTATION_VIDEO_AVAILABILITY_ENABLED=true` for new video availability. Both
default false. `LIVEKIT_TOKEN_TTL=PT2M` is a short join-token lifetime, not a
session-duration enforcement mechanism. Missing/invalid configuration fails
closed. Existing manual appointment, brief and summary operations remain usable.

Configure a provider webhook destination reaching Consultation's
`POST /internal/v1/video/livekit/webhook`, with its signed Authorization header
and unmodified `application/webhook+json` body. Cloud cannot reach localhost.
Local execution needs an operator-approved HTTPS callback/tunnel that forwards
only this destination and retains no sensitive body. Do not expose unrelated
internal APIs or use a staging deployment as a workaround. The official receiver
verifies the signature and body digest; a MentalBridge bearer token is not its
authentication mechanism. Unsigned, tampered or expired deliveries are rejected.

The receiver dispatches only after signature and raw-body digest verification.
Room-only `room_started` callbacks are acknowledged without attendance;
`room_finished` retains only room evidence, with no participant identity, SID
or role. Attendance `participant_joined` / `participant_left` callbacks require
both participant identity and SID. Other verified event types are safely ignored.
No room callback alone confirms an appointment or settles its held credit.

No recording, egress, transcription, AI Agent or automatic AI participant is
enabled. Provider room names and identities are opaque. No brief, summary,
patient name, email or clinical information is sent as media metadata.

## Exercise a real consultation

Use disposable synthetic USER and currently approved SPECIALIST accounts, a
valid paid test entitlement/credit and an accepted `IN_APP_VIDEO` appointment.
Use existing availability, booking and decision contracts; do not bypass them
with direct durable-data edits. ConsultationBrief sharing and SessionSummary
authorization remain their existing independent flows.

1. Before `startsAt - 5 minutes`, the session endpoint reports too early.
2. In the five-minute waiting interval, test camera/microphone locally. There
   must be no provider token/connection or attendance yet.
3. During `[startsAt, endsAt)`, explicitly join as each participant. Each fresh
   access request independently checks assigned actor, role, approval, held
   credit and appointment status. The other actor may join first.
4. Test User Web ↔ Specialist Web, then each Web ↔ Android direction when a
   compatible development build/device is available. Check permission denial,
   unavailable devices, toggles, audio playback, safe leave and reconnect.
5. Leave/rejoin without changing the appointment room. Reconnect must not
   extend the booked end. New access after end/cancellation is rejected.
6. The assigned Specialist explicitly confirms actual session end using
   `video-completion`, quoted current `If-Match`, stable `Idempotency-Key` and
   exactly `{ "confirmation": "CONSULTATION_FINISHED" }`. Neither participant
   leaving nor a provider event invokes that command automatically.
7. Check the authoritative response. Pending verification keeps the exact credit
   HELD. Only verified closure, consistent paired signed events, at least 60
   seconds common attendance, explicit confirmation and the existing business
   invariants create one immutable completion fact, one credit consumption and
   one existing MB-516 earning. Replay must not duplicate any of these.
8. Refresh the appointment/session and verify persisted state. Do not interpret
   elapsed video time as clinical completion or copy provider tokens into proof.

Record only head SHA, synthetic scenario names, timestamps, pass/fail and sanitized
screenshots. Do not retain JWTs, credentials, webhook bodies or personal content.
A prototype or fake-provider test is not evidence of a successful Cloud call.

## Failure and reconciliation

The room adapter has 1-second connect, 3-second read and 4-second per-call
deadlines, no automatic HTTP retry and a bounded circuit breaker. Provider calls
run outside database transactions. Closure revokes both identities with an
explicit fresh seconds-based cutoff, including same-second token issuance, and
deletes the room. Keep backend clocks synchronized. New application tokens are
short-lived; LiveKit refresh/reconnect semantics mean token expiry alone does
not disconnect an existing call. An outage may delay server closure.

The scheduler checks scheduled ends/closure and pending verification every five
seconds, with bounded 100-row batches and 15-second closure/30-second evidence
retry cursors. Provider failure or inconsistent/missing pairs never becomes a
no-show/completion inference. A closure uncertainty remains fail-closed even
after a later successful retry. After 24 hours, unresolved sessions are flagged
for manual evidence review, without releasing/refunding credit or creating
earning. The manual-resolution workflow needs separate review before production;
existing settled-appointment disputes are not repurposed for pending sessions.

The five-minute evidence grace allows late occurrence timestamps after actual
end; it is not five extra call minutes or a required wait before confirmation.
Transport-delayed signed delivery can still reconcile events that occurred in
that permitted window. Conflicting duplicate IDs are retained as uncertainty;
exact duplicates are no-ops, and reconnect intervals are unioned rather than
double counted. A completed fact remains historical; existing dispute handling
may subsequently adjust the credit without deleting that fact.

## Metadata retention and operational limits

An hourly bounded job removes LiveKit participation events after 90 days for
closed terminal rooms when neither the close nor the latest received evidence
is newer than the cutoff. It replaces room/participant mapping identifiers so
old webhook replay cannot restore expired evidence. Any recorded dispute or
explicit valid `retention_hold_reason` prevents cleanup. Authorized operators
must arrange a valid hold before expiry when legally required; no automatic
indefinite hold is invented. Appointment, completion/confirmation audit,
ConsultationBrief, SessionSummary and financial history are not deleted.

Check current Free-plan quotas/capacity in the project's Cloud dashboard before
manual tests. Quotas and provider availability are not guarantees, and no paid
upgrade is authorized. Before production review legal/privacy requirements,
Cloud region/subprocessors, retention holds and pending-session resolution.

## Automated verification checkpoint

`consultation-service` Maven `verify` ran on this worktree with UTC test time:
212 discovered tests, 210 passed, 2 opt-in commercial/Cloud journey tests
skipped, zero failures/errors. New coverage uses real disposable PostgreSQL and
fake provider transport: authorization, grants/TTL, signed webhook digest,
duplicates/order/reconnect, retention/holds, explicit early confirmation,
pending/late evidence, concurrency, settlement/dispute invariants and sanitized
SDK failures. No Cloud media connection or Android runtime was executed by
those tests. Final PRs must record their exact heads and current verification.

## Opt-in real Cloud journey

Run `node scripts/verify-livekit-cloud-config.mjs` from the backend root for a
read-only credential check. It calls RoomService without creating a room and
never prints credentials, tokens, room names or provider payloads. This probe
alone is not a successful-call test.

Build `mentalbridge-frontend/mentalbridge` with `npm run build`, then run from
this backend root (adjust absolute paths for the local checkout):

```powershell
mvn -f consultation-service/pom.xml test '-Dtest=LiveKitCloudJourneyTests' '-Dmb384.cloud=true' '-Dmb384.webhook=true' '-Duser.timezone=UTC' '-Dmb384.backend=E:/mentalbridge-repo/mentalbridge-backend' '-Dmb384.frontend=E:/mentalbridge-repo/mentalbridge-frontend/mentalbridge'
```

The controlled fixture starts real Consultation HTTP and disposable PostgreSQL,
with synthetic approved Specialist/entitlement prerequisites and a test-only
Identity JWT/current-account fixture. Actual public BFF contracts perform slot
creation, booking, acceptance, access and Specialist confirmation. The test
clock skips only booking lead time; provider attendance uses real wall time.
Two Chrome contexts use synthetic camera/microphone input, not human patients.
They connect to the existing Cloud project and require incoming RTP audio/video
in both directions, >65 seconds common connection, toggles and leave/rejoin.
Real signed webhook pairs must reconcile to verified completion and exactly
one credit consumption/MB-516 earning. No attendance/webhook rows are fabricated.
This is not Identity-login E2E or an Android media-call proof.

For the approved temporary callback, run a free tunnel such as:

```powershell
cloudflared tunnel --url http://127.0.0.1:3188 --no-autoupdate
```

Add its temporary HTTPS URL plus `/internal/v1/video/livekit/webhook` in the
existing Cloud project's webhook settings, using the matching Signing API key.
Port 3188 is a loopback, path-filtered proxy that opens only while the opt-in
journey is running; fixture and BFF ports are never exposed. The URL belongs
to operator configuration, not application logic. Do not rebuild the standalone
web app while this journey runs. Cloud tests are disabled in ordinary CI and
never receive secrets from untrusted PRs.

Sanitized screenshots, exact source revisions/clean-state flags, RTP counters
and manifest are retained locally under frontend
`mentalbridge/test-results/mb384-livekit-cloud/`; no raw event or token is saved.
The initial local real Cloud checkpoint on 2026-10-13 passed media, 7 signed
events, explicit confirmation, persisted completion and exactly-one settlement.
Final PR verification must name the exact subsequently committed heads.

After testing, stop the tunnel and remove its temporary Cloud webhook. Moving
to production later requires an approved HTTPS reverse-proxy route to the same
endpoint, injected production credentials and updated Cloud webhook settings;
the handler does not need rewriting. Separate dev/prod LiveKit projects and
credentials are recommended before production; MB-384 creates none and deploys
neither staging nor production.
