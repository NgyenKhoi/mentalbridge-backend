# Care Service

Care owns user profiles, platform consent decisions, questionnaires, assessment submissions and results, deterministic safety/support policy, intervention, and follow-up. Safety-critical scoring and guidance remain local and do not depend on Eureka, OpenFeign, Kafka, Redis, AI, or notification availability.

## MB-88 foundation

The canonical [`care-service-v1.yaml`](../contracts/openapi/care-service-v1.yaml) contract marks questionnaire retrieval plus authenticated and anonymous PHQ-9 assessment paths as `implemented`. Profile and platform-consent paths remain `planned`. MB-89 adds the runtime handlers without publishing an unreviewed Vietnamese questionnaire or user-facing safety text.

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

The reference-data migrations publish immutable English and controlled-Capstone Vietnamese PHQ-9 definitions. Each contains nine questions, item 9 marked as the safety item, four ordered response choices, standard score bands, and auditable source provenance. The default `vi-VN` API definition is `phq9-vi-vn-capstone-v1`; its archived source URI, retrieval timestamp, use statement, and SHA-256 checksum are stored with the database row and recorded in the PHQ-9 policy. This publication is approved only for controlled local/demo Capstone use and does not represent production clinical/domain approval.

Assessment answer text must never be copied into outbox payloads, logs, errors, metrics, or unrestricted audit metadata.

## Configuration

| Variable | Required | Purpose | Safe local example |
| --- | --- | --- | --- |
| `EUREKA_DEFAULT_ZONE` | Production | Eureka registry endpoint shared by Spring services | `http://localhost:8761/eureka/` |
| `CARE_DB_URL` | Yes | Care-owned PostgreSQL JDBC URL; production uses a TLS-capable connection | `jdbc:postgresql://localhost:5432/mentalbridge_care` |
| `CARE_DB_USERNAME` | Yes | Care-owned PostgreSQL login | `mentalbridge_care` |
| `CARE_DB_PASSWORD` | Yes | Care PostgreSQL password injected outside source control | `replace-with-a-local-secret` |
| `IDENTITY_JWT_ISSUER` | Yes | Expected issuer for Identity access tokens | `https://identity.local.mentalbridge` |
| `IDENTITY_JWT_AUDIENCE` | Yes | Required Care API audience | `mentalbridge-api` |
| `IDENTITY_JWT_PUBLIC_KEY` | Yes | X.509 RSA public key used to verify Identity tokens | `replace-with-x509-pem-public-key` |
| `CARE_ANONYMOUS_SESSION_TTL` | Yes | Versioned ISO-8601 lifetime applied to anonymous sessions and their results | `PT30M` for controlled local/demo use; production value requires retention review |
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
- Exact Vietnamese questionnaire content is published as `phq9-vi-vn-capstone-v1` for controlled local/demo use. Production domain review, support-tier mapping, intervention content, production consent/retention, and minimum-age expansion remain separate unresolved feature or deployment decisions.
- No endpoint may imply emergency dispatch, continuous human monitoring, or guaranteed notification delivery.

The canonical policy register is maintained in [`docs/policies/`](../docs/policies/). `MB-CAPSTONE-SCREENING-PUBLICATION-001` defines a bounded evidence gate for controlled local/demo publication; a Capstone decision is not executable production approval.

Questionnaire publication no longer depends on the support/intervention catalogue or specialist workflow. Each capability follows its own gate. Publishing localized content must update the source artifact/provenance record, tests, configuration, append-only migrations/data dictionary when needed, and this README together. Public real-user deployment additionally requires production privacy, retention, security, legal, safety-content, and operational review.

MB-89 implements only the deterministic contract that can be executed without invented content. `vi-VN` questionnaire retrieval now resolves the Capstone-published definition. Deployment supplies an explicit anonymous-session TTL and safety-policy version; production values have no default. Exact safety guidance and support interventions remain unavailable rather than being generated by the service until their separate gates pass.

## Integration

- Inbound REST: the canonical Care OpenAPI file is the source of truth.
- Outbound REST: exceptional current Identity facts may later use a consumer-owned OpenFeign adapter with explicit deadlines and fail-closed behavior; no such runtime dependency is implemented by MB-88.
- Async: future assessment, support, consent, intervention, and follow-up events use Kafka with a transactional outbox and language-neutral schemas.
- Discovery: Care registers as `care-service`; registry metadata never grants authorization.

## Run and test

```powershell
.\mvnw.cmd test
```

The PostgreSQL integration suite applies Liquibase to a disposable real PostgreSQL database and validates owner isolation, seed data, consent history/idempotency, authenticated-versus-anonymous ownership, scoring boundaries, item-9 independence, token isolation/expiry, answer/result ranges, questionnaire version uniqueness, and minimized outbox payloads. Live Eureka registration is disabled in tests. The versioned `care.assessment.submitted` event contract exists, while a Kafka relay remains a separate delivery slice; scoring never waits for a broker.
