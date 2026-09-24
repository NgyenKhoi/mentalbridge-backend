# Care Service

Care owns user profiles, independent `PRIVACY_POLICY` and `AI_PROCESSING` consent decisions, questionnaires, assessment submissions and results, deterministic safety/support policy, SupportEvaluation, persisted Support Guides, the single official SupportPlan, reassessment, and follow-up. Story 1103 exposes immutable coarse v1 evaluation. MB-335 adds `/api/v2/support-evaluations` with exact PHQ-9/GAD-7 provenance, two independent domain contributions, and separate PHQ-9 item-9 safety evidence. MB-372 adds paid deterministic draft creation and current-draft reload. MB-373 adds bounded admitted-choice replacement, exact revalidation, explicit activation idempotency, and authoritative current-plan reload. MB-513 adds deterministic local-time schedules and persisted activity occurrences. MB-374 completes the owner lifecycle with optional coded completion context and immutable terminal history/detail reads. MB-376 adds versioned owner-only completion, skip, reopen, visibility, helpfulness, barrier, private reflection, and deletion semantics on exact occurrences. MB-386 composes immutable owner snapshots with the implemented pre-ADR-0022 dimensions: local standardized screening trend, minimized Journal/AI context, explicitly reusable SupportPlan engagement, and occurrence helpfulness/reflection. The canonical target adds a distinct explicit user-authored reassessment self-report and treats occurrence helpfulness/reflection as supporting evidence only. Care never creates a global severity, treatment-adherence score, recovery score, specialist-monitoring feed, or AI-controlled state. Safety-critical scoring and evaluation remain local and do not depend on Eureka, Kafka, Redis, AI, or notification availability.

## MB-88 foundation

The canonical [`care-service-v1.yaml`](../contracts/openapi/care-service-v1.yaml) contract marks profile, independent privacy/AI-processing disclosure and consent, questionnaire retrieval, assessment history, and authenticated/anonymous screening paths as `implemented`. MB-367 adds the backend-only `AI_PROCESSING` decision and minimal current authorization response without adding frontend consent UI or production-provider approval.

MB-205 adds authenticated descriptive progress for a selected owned assessment. Care compares it only with the immediately preceding non-voided result for the same instrument and identical scoring version, ordered by submission instant and assessment ID. The response contains arithmetic score direction, raw delta, band transition and elapsed duration without safety-status comparison, clinical interpretation, causation or optional-service side effects. Missing, voided or incompatible evidence returns `INSUFFICIENT_COMPARABLE_DATA`; missing and cross-owner identifiers share the same `ASSESSMENT_NOT_FOUND` response.

The read-only query lives in the cohesive `progress` feature package. The existing partial index `ix_assessment_submission_user_history (user_id, submitted_at DESC, id DESC) WHERE user_id IS NOT NULL AND voided_at IS NULL` already supports the owner/time/tie-breaker scan, so MB-205 adds no table, field, index or Liquibase changeset.

MB-386 adds `POST /api/v1/reassessment-summaries`, owner-only current/detail/history reads, and the immutable `reassessment_summary` snapshot. The request selects one owned current PHQ-9 result, one owned current GAD-7 result, one Journal/AI longitudinal analysis, and two equal non-overlapping 7-31 day periods. Care selects the immediately preceding compatible result for each instrument and calculates arithmetic direction locally. The Journal/AI projection must attribute the same analysis and periods; missing/deleted evidence, consent denial, malformed payloads, timeouts, and dependency failures become an explicit persisted `UNAVAILABLE` journal dimension. Sparse coverage remains `INSUFFICIENT_DATA`. SupportPlan evidence is selected by occurrence `scheduled_at` inside the periods and only when the owner previously set `summary_reuse_approved=true`; private reflection is included only under that same approval. Reads never re-query mutable or deleted sources. The actor-facing four-card screen remains dependent Story 6502 rather than this backend owner slice.

MB-386 remains a compatibility baseline under its exact policy/source
provenance. ADR 0022 does not rewrite those snapshots. New canonical summaries
require an explicit reassessment self-report authored by the user; the additive
contract/persistence/runtime for that input is not implemented yet.

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

The Care Liquibase changelog owns:

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
| `support_policy_definition` and policy reference tables | Immutable compatible questionnaire versions, localized band meanings, and bounded support-tier guidance |
| `support_evaluation` | Immutable deterministic v1 coarse result for one explicit owned PHQ-9/GAD-7 evidence pair; not sufficient to select a SupportPlan |
| `support_evaluation_request` | Per-user idempotency aliases resolving retries to the immutable evaluation |
| `support_evaluation_v2_policy_definition` and `support_evaluation_v2_eligible_definition` | Immutable v2 policy provenance and exact compatible questionnaire/domain allow-list |
| `support_evaluation_v2` | Immutable v2 owner, explicit evidence pair, policy version, and evaluation instant |
| `support_evaluation_v2_domain` | Two immutable instrument/domain/level/pathway/reason snapshots used for independent composition |
| `support_evaluation_v2_safety` | Independent PHQ-9 item-9 status and safety-policy snapshot without a raw answer |
| `support_evaluation_v2_request` | Per-user v2 idempotency aliases; separate namespace from v1 keys |
| `support_plan` | One Care-owned paid proposal/current-plan snapshot with exact source, entitlement, rationale, safety, lifecycle instants, optional coded completion reason, and optimistic version provenance; terminal rows are immutable owner history |
| `support_plan_template_family` | Ordered immutable domain template families composed into the draft |
| `support_plan_slot` | Ordered bounded slots; core selection is required while an optional selection may be explicitly removed |
| `support_plan_slot_alternative` | Server-admitted exact alternatives for a stored slot; not client-authored choices |
| `support_plan_request` | Per-user idempotency aliases that replay the same current draft |
| `support_plan_command` | Owner-scoped idempotent activation outcome plus exact revalidation provenance |
| `support_plan_command_selection` | Ordered exact resource-version intent committed by activation |
| `support_plan_activity_schedule` | Versioned recurrence and local-time/source snapshot owned by one plan |
| `support_plan_activity_occurrence` | Deterministic dated activity plus versioned owner engagement/visibility with exact source provenance |
| `reassessment_summary` | Immutable owner-scoped MB-386 compatibility snapshot with request idempotency, exact periods, external Journal/AI analysis provenance, reusable occurrence evidence, and explicit unavailable/insufficient states; the explicit reassessment self-report amendment still requires additive persistence |
| `outbox_event` | Minimal integration fact persisted in the aggregate transaction |

The reference-data migrations publish immutable English PHQ-9, current controlled-Capstone Vietnamese PHQ-9 v2, and Vietnamese GAD-7 definitions. PHQ-9 v1 remains readable as a retired immutable definition so historical results reopen against their original wording and bands. GAD-7 contains seven questions, the approved four-choice self-administered mapping, standard `0..21` bands, explicit non-applicable safety semantics, and auditable source provenance. These publications are approved only for controlled local/demo Capstone use and do not represent production clinical/domain approval.

The v1 support-routing policy serves Vietnamese screening meanings with content version `mb-screening-meaning-vi-vn-v2`. The expanded copy explains what each PHQ-9 or GAD-7 band reflects over 14 days, keeps the two instruments separate, and preserves the non-diagnostic limitation. The routing tier and safety behavior are unchanged.

Assessment answer text must never be copied into outbox payloads, logs, errors, metrics, or unrestricted audit metadata.

## Configuration

| Variable | Required | Purpose | Safe local example |
| --- | --- | --- | --- |
| `EUREKA_DEFAULT_ZONE` | Production | Eureka registry endpoint shared by Spring services | `http://localhost:8761/eureka/` |
| `EUREKA_CLIENT_ENABLED` | No | Enables Eureka registration; disable it when running Care by itself locally | `false` |
| `CONTENT_RESOURCE_ELIGIBILITY_BASE_URL` | Local/test only | Optional direct Content URL; leave empty outside tests so OpenFeign resolves `content-notification-service` through Eureka | `http://localhost:3003` |
| `CONTENT_RESOURCE_ELIGIBILITY_CONNECT_TIMEOUT` | No | Bounded TCP connection deadline for exact eligibility queries | `PT0.5S` |
| `CONTENT_RESOURCE_ELIGIBILITY_READ_TIMEOUT` | No | Total response-read deadline for a bounded batch | `PT2S` |
| `CONTENT_RESOURCE_ELIGIBILITY_MAX_ATTEMPTS` | No | Total safe attempts for the read-only batch POST, from 1 through 3 | `2` |
| `CONTENT_RESOURCE_ELIGIBILITY_RETRY_WAIT` | No | Positive base delay for bounded exponential retry with 20% jitter | `PT0.1S` |
| `CONTENT_RESOURCE_ELIGIBILITY_CIRCUIT_WINDOW_SIZE` | No | Resilience4j count-based breaker window | `10` |
| `CONTENT_RESOURCE_ELIGIBILITY_CIRCUIT_MINIMUM_CALLS` | No | Calls required before the breaker may open | `5` |
| `CONTENT_RESOURCE_ELIGIBILITY_CIRCUIT_FAILURE_RATE` | No | Percentage of dependency failures that opens the breaker | `50` |
| `CONTENT_RESOURCE_ELIGIBILITY_CIRCUIT_OPEN_DURATION` | No | Bounded open interval before half-open probes | `PT10S` |
| `CONTENT_RESOURCE_ELIGIBILITY_CIRCUIT_HALF_OPEN_CALLS` | No | Permitted half-open probes | `2` |
| `CONTENT_SAFETY_DIRECTORY_BASE_URL` | Local/test only | Optional direct Content URL for the reviewed directory; leave empty outside tests so OpenFeign uses Eureka | `http://localhost:3003` |
| `CONTENT_SAFETY_DIRECTORY_CONNECT_TIMEOUT` | No | Bounded TCP connection deadline for directory lookup | `PT0.5S` |
| `CONTENT_SAFETY_DIRECTORY_READ_TIMEOUT` | No | Bounded response-read deadline before Care returns its local fallback | `PT1S` |
| `CONSULTATION_ENTITLEMENT_BASE_URL` | Local/test only | Optional direct Consultation URL; leave empty outside tests so OpenFeign resolves `consultation-service` through Eureka | `http://localhost:8082` |
| `CONSULTATION_ENTITLEMENT_CONNECT_TIMEOUT` | No | Bounded TCP connection deadline for the authoritative current entitlement read | `PT0.5S` |
| `CONSULTATION_ENTITLEMENT_READ_TIMEOUT` | No | Total response-read deadline for current entitlement | `PT2S` |
| `JOURNAL_AI_LONGITUDINAL_BASE_URL` | Local/demo only | Direct Journal/AI URL because the Node service does not register with Eureka | `http://localhost:3000` |
| `JOURNAL_AI_LONGITUDINAL_CONNECT_TIMEOUT` | No | Bounded connection deadline for minimized reassessment evidence | `PT0.2S` |
| `JOURNAL_AI_LONGITUDINAL_READ_TIMEOUT` | No | Response-read deadline before one Journal/AI attempt times out | `PT0.8S` |
| `JOURNAL_AI_LONGITUDINAL_MAX_ATTEMPTS` | No | Total attempts for the idempotent GET, one or two | `2` |
| `JOURNAL_AI_LONGITUDINAL_RETRY_WAIT` | No | Positive base delay for bounded transient retry with jitter | `PT0.1S` |
| `JOURNAL_AI_LONGITUDINAL_CIRCUIT_WINDOW_SIZE` | No | Reassessment projection breaker window | `10` |
| `JOURNAL_AI_LONGITUDINAL_CIRCUIT_MINIMUM_CALLS` | No | Calls required before the breaker may open | `5` |
| `JOURNAL_AI_LONGITUDINAL_CIRCUIT_FAILURE_RATE` | No | Percentage of transport/malformed failures that opens the breaker | `50` |
| `JOURNAL_AI_LONGITUDINAL_CIRCUIT_OPEN_DURATION` | No | Bounded breaker-open interval | `PT10S` |
| `JOURNAL_AI_LONGITUDINAL_CIRCUIT_HALF_OPEN_CALLS` | No | Permitted half-open probes | `2` |
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
- Current Vietnamese questionnaire content is published as `phq9-vi-vn-capstone-v2` for `DEPRESSIVE_SYMPTOMS` and `gad7-vi-vn-adult-v1` for `ANXIETY_SYMPTOMS` in controlled local/demo use. PHQ-9 v1 is retired without mutation. GAD-7 returns `NOT_APPLICABLE` with null safety fields instead of a false PHQ-style safety result. Safety remains cross-cutting; no combined score or global severity exists. The deterministic v1 support-tier mapping and additive `mb-support-routing-capstone-v2` domain-aware evaluation are published for controlled Capstone use. MB-511 adds the all-tier one-time `mb-support-guide-capstone-v1` runtime with immutable exact provenance and approved-copy fallback. MB-372/MB-373 implement paid draft proposal, bounded choice replacement, explicit activation, and current-plan reload under ADR 0013. Pause/resume, completion, replacement, specialist actions, production consent/retention, production domain review, and minimum-age expansion remain separate gates.
- No endpoint may imply emergency dispatch, continuous human monitoring, or guaranteed notification delivery.

The canonical policy register is maintained in [`docs/policies/`](../docs/policies/). `MB-CAPSTONE-SCREENING-PUBLICATION-001` defines a bounded evidence gate for controlled local/demo publication; a Capstone decision is not executable production approval.

Questionnaire publication does not depend on SupportPlan or specialist workflow. Each capability follows its own gate. Publishing localized content must update the source artifact/provenance record, tests, configuration, append-only migrations/data dictionary when needed, and this README together. ADR 0012 requires the forward plan to be system-proposed from domain-aware evaluation and exact eligible content; ADR 0013 freezes the product policy and still does not change active v1 contracts or rows. Public real-user deployment additionally requires production privacy, retention, security, legal, safety-content, and operational review.

MB-89 implements the deterministic PHQ-9 runtime. MB-178 adds the backend-owned, versioned privacy disclosure and `PRIVACY_POLICY`; Story 1102 makes `privacy-capstone-v3` current for PHQ-9/GAD-7. MB-367 adds the independent backend-only `AI_PROCESSING` stream at `ai-processing-capstone-v1` for exact-revision and bounded-longitudinal journal analysis. Its user-facing consent UI remains follow-up work. Research, marketing, specialist sharing, production retention/deletion/export, and automatic clinical reminders remain unavailable rather than being inferred from either consent.

## Integration

- Inbound REST: the canonical Care OpenAPI file is the source of truth.
- Inbound Support Guide REST: `care-support-guide-v1.yaml` defines authenticated generation, owner-only history/detail, idempotency, immutable provenance, and stable resource-resolution states. It is intentionally separate from SupportPlan lifecycle.
- Outbound REST: the consumer-owned OpenFeign Resource Eligibility v1 adapter queries Content with the end-user bearer context, explicit correlation, 500 ms connect and 2 s read deadlines, bounded exponential transient retry with jitter, and a Resilience4j circuit breaker. HTTP 429 is not retried because the provider contract does not define `Retry-After`; timeout, dependency errors, malformed payloads and enum evolution map every candidate to `UNAVAILABLE`. Callers must commit no proposal mutation. No Care transaction spans the call.
- Outbound reassessment REST: the consumer-owned Journal/AI adapter forwards the verified end-user bearer and correlation ID to the canonical `REASSESSMENT_SUMMARY` projection. It applies a 200 ms connect deadline, 800 ms read deadline, at most one transient retry, and a separate circuit breaker. Startup rejects overrides whose conservative two-attempt budget exceeds 2.5 seconds, preserving time for Care to return the explicit safe fallback before the three-second caller deadline. It validates attribution, exact periods, source counts, coverage sufficiency, directions, and provenance before persistence. No transaction spans the remote call; every safe fallback is snapshotted explicitly.
- Async: future assessment, support, consent, intervention, and follow-up events use Kafka with a transactional outbox and language-neutral schemas.
- Discovery: Care registers as `care-service`; registry metadata never grants authorization.

## Run and test

```powershell
.\mvnw.cmd test
```

The PostgreSQL integration suite applies Liquibase to a disposable real PostgreSQL database and validates owner isolation, profile optimistic concurrency, append-only consent history/idempotency/revocation, disclosure enforcement, stable history pagination, deterministic compatible progress selection, support-routing evidence validation and concurrency/idempotency, Support Guide safety/resource failure/history/ownership behavior, immutable Reassessment Summary creation/replay/current/history behavior and source-change independence, policy lookup serialization and fallback behavior, seed data, authenticated-versus-anonymous ownership, scoring boundaries, item-9 independence, token isolation/expiry, answer/result ranges, questionnaire version uniqueness, and minimized outbox payloads. Resource eligibility and Journal/AI consumer tests cover auth/correlation propagation, documented failures, deadlines, bounded retry, breaker opening, malformed data, response attribution, source deletion, consent denial, and safe fallback. Live Eureka registration is disabled in tests. The versioned assessment and support-tier event contracts exist, while a Kafka relay remains a separate delivery slice; scoring, progress, support routing, Support Guide safety, and local reassessment dimensions never wait for a broker.
