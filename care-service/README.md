# Care Service

Care owns user profiles, platform consent decisions, questionnaires, assessment submissions and results, deterministic safety/support policy, intervention, and follow-up. Safety-critical scoring and guidance remain local and do not depend on Eureka, OpenFeign, Kafka, Redis, AI, or notification availability.

## MB-88 foundation

The canonical [`care-service-v1.yaml`](../contracts/openapi/care-service-v1.yaml) contract marks profile, controlled-Capstone privacy disclosure/consent, questionnaire retrieval, assessment history, and authenticated/anonymous PHQ-9 paths as `implemented`. MB-178 completes the profile, consent-history, disclosure-acknowledgement, bounded anonymous lifetime, and user-initiated reassessment slice without claiming production retention approval.

MB-205 adds authenticated descriptive progress for a selected owned assessment. Care compares it only with the immediately preceding non-voided result for the same instrument and identical scoring version, ordered by submission instant and assessment ID. The response contains arithmetic score direction, raw delta, band transition and elapsed duration without safety-status comparison, clinical interpretation, causation or optional-service side effects. Missing, voided or incompatible evidence returns `INSUFFICIENT_COMPARABLE_DATA`; missing and cross-owner identifiers share the same `ASSESSMENT_NOT_FOUND` response.

The read-only query lives in the cohesive `progress` feature package. The existing partial index `ix_assessment_submission_user_history (user_id, submitted_at DESC, id DESC) WHERE user_id IS NOT NULL AND voided_at IS NULL` already supports the owner/time/tie-breaker scan, so MB-205 adds no table, field, index or Liquibase changeset.

The contract establishes these boundaries:

- the verified JWT subject is the only authenticated profile and assessment owner;
- only `USER` accounts may use the authenticated profile, consent, and assessment flows;
- profile replacement uses optimistic concurrency and never accepts an account ID from the request;
- consent decisions are append-only, policy-versioned, and idempotent per user and consent type;
- specialist access is a separate future scoped-grant workflow, not a broad consent toggle;
- assessment requests contain only a questionnaire-definition ID and complete `0..3` answers;
- Care computes score, screening band, scoring version, safety status, and safety-policy version;
- submission, answers, result, and outbox record commit atomically when runtime behavior is implemented;
- an anonymous session is authorized by a high-entropy token stored only as a hash, expires at a policy-owned instant, and has no account/claim field;
- anonymous results cannot be silently attached to a later account.

Errors use RFC 9457 Problem Details with stable `code` and `correlationId` fields. Assessment and consent commands require `Idempotency-Key`; a retry with the same owner, key, and request returns the original outcome, while a different request conflicts.

## PostgreSQL ownership

Create the service-owned `mentalbridge_care` database before running this module. Liquibase connects directly to that database and uses its default `public` schema. It never creates a database, schema named `care`, or cross-service foreign key.

The MB-88 changelog owns:

| Table | Purpose |
| --- | --- |
| `user_profile` | Care-owned non-credential profile with optimistic versioning |
| `consent_decision` | Append-only versioned consent evidence with owner-scoped idempotency |
| `anonymous_assessment_session` | Short-lived isolated session with hashed bearer credential |
| `questionnaire_definition` | Versioned instrument metadata, scoring identity, provenance, and publication state |
| `questionnaire_question` | Ordered version-owned item wording and safety-item marker |
| `questionnaire_score_band` | Auditable score-to-screening-level ranges |
| `assessment_submission` | Immutable owner, questionnaire version, request hash, idempotency key, and anonymous retention deadline |
| `assessment_answer` | One validated `0..3` answer tied to the same definition as its submission |
| `assessment_result` | Server-owned score, band, independent safety status, scoring/safety-policy versions, and disclaimer code |
| `outbox_event` | Minimal integration fact persisted in the aggregate transaction |

The reference-data migrations publish immutable English PHQ-9, current controlled-Capstone Vietnamese PHQ-9 v2, and Vietnamese GAD-7 definitions. PHQ-9 v1 remains readable as a retired immutable definition so historical results reopen against their original wording and bands. GAD-7 contains seven questions, the approved four-choice self-administered mapping, standard `0..21` bands, explicit non-applicable safety semantics, and auditable source provenance. These publications are approved only for controlled local/demo Capstone use and do not represent production clinical/domain approval.

Assessment answer text must never be copied into outbox payloads, logs, errors, metrics, or unrestricted audit metadata.

## Configuration

| Variable | Required | Purpose | Safe local example |
| --- | --- | --- | --- |
| `EUREKA_DEFAULT_ZONE` | Production | Eureka registry endpoint shared by Spring services | `http://localhost:8761/eureka/` |
| `EUREKA_CLIENT_ENABLED` | No | Enables Eureka registration; disable it when running Care by itself locally | `false` |
| `CARE_DB_URL` | Yes | Care-owned PostgreSQL JDBC URL; production uses a TLS-capable connection | `jdbc:postgresql://localhost:5432/mentalbridge_care` |
| `CARE_DB_USERNAME` | Yes | Care-owned PostgreSQL login | `mentalbridge_care` |
| `CARE_DB_PASSWORD` | Yes | Care PostgreSQL password injected outside source control | `replace-with-a-local-secret` |
| `IDENTITY_JWT_ISSUER` | Yes | Expected issuer for Identity access tokens | `https://identity.local.mentalbridge` |
| `IDENTITY_JWT_AUDIENCE` | Yes | Required Care API audience | `mentalbridge-api` |
| `IDENTITY_JWT_PUBLIC_KEY` | Yes | X.509 RSA public key used to verify Identity tokens | `replace-with-x509-pem-public-key` |
| `CARE_ANONYMOUS_SESSION_TTL` | Yes | Sliding inactivity lifetime applied after valid anonymous activity | `PT30M` for controlled local/demo use |
| `CARE_ANONYMOUS_SESSION_MAXIMUM_LIFETIME` | Yes | Absolute lifetime from anonymous session creation; activity cannot extend beyond it | `PT2H` for controlled local/demo use |
| `CARE_PHQ9_SAFETY_POLICY_VERSION` | Yes | Exact environment-approved policy version persisted with every runtime PHQ-9 result | `MB-SAFETY-PHQ9-001/1.0-capstone` only after the Capstone evidence gate passes |
| `CARE_IDEMPOTENCY_HMAC_KEY` | Yes | Random secret of at least 32 characters used to protect assessment request fingerprints from offline enumeration | Secret-manager value; never commit it |

Application startup validates the Liquibase-owned schema through Hibernate and does not run migrations. Local `.env` loading is optional; real environment variables take precedence, and CI/production disable dotenv loading.

```powershell
$env:CARE_DB_URL='jdbc:postgresql://<host>:5432/mentalbridge_care?sslmode=require'
$env:CARE_DB_USERNAME='<care-username>'
$env:CARE_DB_PASSWORD='<care-password>'
./mvnw.cmd liquibase:validate
./mvnw.cmd liquibase:status
./mvnw.cmd liquibase:updateSQL
./mvnw.cmd liquibase:update
```

`updateSQL` reviews generated SQL without mutating the database. `update` applies pending changesets and records them in `mentalbridge_care.public.databasechangelog`.

## Safety and policy status

The foundation records facts needed by later governed behavior without silently deciding open policy:

- PHQ-9 scoring is deterministic and server-owned; the stored result is a screening result, not a diagnosis.
- A positive versioned safety item is persisted independently of the total score so later policy cannot ignore it.
- ADR 0009 fixes item-9 positivity (`answer >= 1`), keeps it independent from the screening band, prohibits automatic human/emergency notification, and removes the hotline catalogue.
- Current Vietnamese questionnaire content is published as `phq9-vi-vn-capstone-v2` and `gad7-vi-vn-adult-v1` for controlled local/demo use. PHQ-9 v1 is retired without mutation. GAD-7 returns `NOT_APPLICABLE` with null safety fields instead of a false PHQ-style safety result. Production domain review, support-tier mapping, intervention content, production consent/retention, and minimum-age expansion remain separate unresolved feature or deployment decisions.
- No endpoint may imply emergency dispatch, continuous human monitoring, or guaranteed notification delivery.

The canonical policy register is maintained in [`docs/policies/`](../docs/policies/). `MB-CAPSTONE-SCREENING-PUBLICATION-001` defines a bounded evidence gate for controlled local/demo publication; a Capstone decision is not executable production approval.

Questionnaire publication no longer depends on the support/intervention catalogue or specialist workflow. Each capability follows its own gate. Publishing localized content must update the source artifact/provenance record, tests, configuration, append-only migrations/data dictionary when needed, and this README together. Public real-user deployment additionally requires production privacy, retention, security, legal, safety-content, and operational review.

MB-89 implements the deterministic PHQ-9 runtime. MB-178 adds the backend-owned, versioned privacy disclosure, append-only `PRIVACY_POLICY` decisions, optimistic Care profile replacement, cursor-based owned history, and explicit reassessment as a new immutable submission. Story 1102 makes `privacy-capstone-v3` current for new PHQ-9/GAD-7 processing while preserving v1/v2 on historical decisions and assessments; authenticated results may support account history, whereas anonymous results remain session-scoped and are never silently attached to a later account. Anonymous activity extends the 30-minute inactivity deadline only up to the two-hour absolute maximum. Registered history is authorized only for synthetic/test/demo use; production retention, deletion, export, specialist sharing, AI/research/marketing consent, and automatic clinical reminders remain unavailable rather than being invented.

## Integration

- Inbound REST: the canonical Care OpenAPI file is the source of truth.
- Outbound REST: exceptional current Identity facts may later use a consumer-owned OpenFeign adapter with explicit deadlines and fail-closed behavior; no such runtime dependency is implemented by MB-88.
- Async: future assessment, support, consent, intervention, and follow-up events use Kafka with a transactional outbox and language-neutral schemas.
- Discovery: Care registers as `care-service`; registry metadata never grants authorization.

## Run and test

```powershell
.\mvnw.cmd test
```

The PostgreSQL integration suite applies Liquibase to a disposable real PostgreSQL database and validates owner isolation, profile optimistic concurrency, append-only consent history/idempotency/revocation, disclosure enforcement, stable history pagination, deterministic compatible progress selection, seed data, authenticated-versus-anonymous ownership, scoring boundaries, item-9 independence, token isolation/expiry, answer/result ranges, questionnaire version uniqueness, and minimized outbox payloads. Live Eureka registration is disabled in tests. The versioned `care.assessment.submitted` event contract exists, while a Kafka relay remains a separate delivery slice; scoring and progress never wait for a broker.
