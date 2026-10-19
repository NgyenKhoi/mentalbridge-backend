# MB-565 assessment, resource, and system notification producer evidence

## Delivered boundary

MB-565 connects three bounded USER notification sources to the existing
Content/Notification inbox without moving source authority:

| Owner fact | Versioned message | Content kind | Preference group | Approved action |
| --- | --- | --- | --- | --- |
| Authenticated assessment result committed | `care.user-notification.fact` / `ASSESSMENT_RESULT_AVAILABLE` | `ASSESSMENT_RESULT_AVAILABLE` | `screeningReassessment` | `OPEN_ASSESSMENTS` → `/assessments` |
| First SupportGuide committed | `care.user-notification.fact` / `SUPPORT_GUIDE_AVAILABLE` | `SUPPORT_GUIDE_AVAILABLE` | `resourceSystem` | `OPEN_SUPPORT_GUIDE` → `/support-guides/{supportGuideId}` |
| USER email verification committed | `identity.user-notification.fact` / `ACCOUNT_ACTIVATED` | `ACCOUNT_STATUS` | `resourceSystem` | none |
| USER account restoration committed | `identity.user-notification.fact` / `ACCOUNT_RESTORED` | `ACCOUNT_STATUS` | `resourceSystem` | none |

Care inserts its facts into `outbox_event` in the same transaction as the
assessment or SupportGuide. Its relay waits for Kafka acknowledgement before
marking a fact published and leaves failed sends retryable. Identity reuses its
existing transactional lifecycle outbox and relay. Anonymous assessments and
SPECIALIST account lifecycle changes do not produce these USER notifications.

The schemas contain only recipient, closed fact type, exact aggregate identity
and version, correlation metadata, and UTC availability time. They exclude
assessment answers, scores, bands, safety state, resource text, account email,
admin reason text, credentials, care data, and provider payloads.

Content validates envelope-to-payload identity and date bindings before any
write. It owns user-facing copy, preference evaluation, safe deep links,
delivery state, retention, and deduplication. The logical identity
`<fact-type>:<aggregate-id>:<aggregate-version>` and a semantic fingerprint
exclude transport message and correlation IDs, so replaying the same fact in a
new Kafka envelope cannot create another inbox item. A conflicting payload for
the same logical identity fails closed. Disabled preferences create only a
`CANCELLED` dedupe record and never become visible through the inbox.

## Contract and persistence artifacts

- `contracts/events/care/user-notification-fact-v1.schema.json`
- `contracts/events/identity/user-notification-fact-v1.schema.json`
- `contracts/openapi/content-notification-service.yaml`
- `content-notification-service/migrations/25_add_general_user_notification_producers.sql`
- `docs/domain-model/canonical-entities.md`
- `docs/database/postgresql-field-data-dictionary.md`

The migration is forward-only and expands the existing notification category
and action constraints. It adds no cross-service foreign key or producer-owned
copy. Older application versions continue to read existing rows; deployment
must apply the Content migration before enabling the new Care relay and Content
consumer.

## Verification

### Review regression: PostgreSQL timestamp precision

Both producers truncate source instants to microseconds before writing their
JSON availability time and the outbox timestamp. Content retains exact text
equality and target/recipient/version binding. Source nanoseconds never round
into a different microsecond or the next second during PostgreSQL persistence.

`UserNotificationPrecisionCrossServiceIT` in each Java owner exercises the
real producer, migrated PostgreSQL outbox, persisted relay reload, Kafka,
Content's production Zod schema and PostgreSQL inbox repository. Assessment,
SupportGuide, activation and restoration each cover `.123456789`, `.123456999`
and `.999999999` source fractions. Every resulting fact creates exactly one
delivered inbox item, including replay with a new transport envelope; a
materially different envelope timestamp is still rejected. The Content-owned
test worker is `scripts/verify-user-notification-precision.mjs`; the required
cross-service CI lane builds Content before running both owner tests.

### Review regression: Identity persistence outage

The lifecycle consumer parks only invalid JSON/schema records and conflicting
logical identities. A valid fact that fails persistence after three local
attempts throws a sanitized retryable error. KafkaJS retries/restarts with a
1s initial delay, exponential backoff capped at 30s and five attempts per
cycle; it cannot advance the failed offset before the transaction succeeds.
Preference initialization for registered USER/SPECIALIST accounts follows the
same recovery rule. No raw fact is copied into diagnostics or a second store.

`verify-account-lifecycle-recovery.mjs`, invoked by Identity's required
cross-service test, denies new connections to the disposable Content database
after the initial replay commits. It observes more than three actual database
failures, asserts the Kafka group offset is unchanged and the DLQ empty, then
restores database access and waits for exactly one durable notification.
It sends a new-envelope replay through Kafka, verifies SPECIALIST exclusion
and the existing cancelled preference outcome, then verifies a malformed fact
advances only after digest-only parking. Focused consumer unit tests also
cover repeated storage failure, preference initialization, recovery, malformed
payload privacy and poison identity conflict classification.

The final branch is verified with each affected module's complete gate plus
focused contract, outbox, integration, replay, preference, and browser parsing
regressions. Exact commands and counts are recorded in the pull requests and
their required exact-head CI runs.
