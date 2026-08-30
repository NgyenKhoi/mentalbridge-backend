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

The reference-data migration publishes one English PHQ-9 definition with nine questions, item 9 marked as the safety item, the four response choices, standard score bands, and a bibliographic source reference. It deliberately does not publish a Vietnamese translation. `vi-VN` remains the product default, but the current-questionnaire API must return not found for that locale until reviewed Vietnamese wording and version provenance are approved.

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
| `CARE_ANONYMOUS_SESSION_TTL` | Yes | Approved ISO-8601 lifetime applied to anonymous sessions and their results | `PT30M` only after policy approval |
| `CARE_PHQ9_SAFETY_POLICY_VERSION` | Yes | Exact approved policy version persisted with every runtime PHQ-9 result | `MB-SAFETY-PHQ9-001/1.0` only after approval |
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

The foundation records facts needed by later approved behavior without silently deciding open policy:

- PHQ-9 scoring is deterministic and server-owned; the stored result is a screening result, not a diagnosis.
- A positive versioned safety item is persisted independently of the total score so later policy cannot ignore it.
- ADR 0009 fixes item-9 positivity (`answer >= 1`), keeps it independent from the screening band, prohibits automatic human/emergency notification, and removes the hotline catalogue.
- Exact reviewed Vietnamese questionnaire/disclaimer/safety wording, support-tier mapping, intervention catalogue, consent text/version ownership, anonymous-session duration, assessment retention/deletion, and minimum-age handling remain unresolved.
- No endpoint may imply emergency dispatch, continuous human monitoring, or guaranteed notification delivery.

The canonical draft policies and approval blockers are maintained in [`docs/policies/`](../docs/policies/). A draft is not executable production configuration.

These decisions must be approved before publishing `vi-VN` content, activating support/intervention behavior, or supplying production policy values. Such a change must update the OpenAPI contract, typed boundary, tests, configuration, migrations/data dictionary when needed, and this README together.

MB-89 implements only the deterministic contract that can be executed without invented content. `vi-VN` questionnaire retrieval continues to return `QUESTIONNAIRE_NOT_FOUND` until reviewed wording is published. Deployment must supply an approved anonymous-session TTL and safety-policy version; neither value has a production default. Exact safety guidance and support interventions remain unavailable rather than being generated by the service.

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
