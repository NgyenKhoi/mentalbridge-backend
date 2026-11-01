# PostgreSQL Shared and Planned Concepts

These sections preserve the former dictionary's conceptual `ai` and `platform` descriptions. They are not separate runtime PostgreSQL databases or deployable service owners, and their inclusion does not establish active persistence. [Journal/AI runtime storage](../mongodb.md) remains owner-defined in MongoDB; shared platform patterns belong within the service that implements the accepted feature.

Check the [canonical domain model](../../domain-model/README.md), [service boundaries](../../agent-guides/module-boundaries.md), and the actual owner migration history before using any conceptual shape. No new database, migration, or ownership is introduced here.

[All PostgreSQL owners](../postgresql-field-data-dictionary.md)

## Conceptual owner `ai` (owner-local `public` tables)

### `ai.analysis_job`

Durable Journal/AI orchestration state for one provider analysis of one journal revision.

| Field | Purpose |
| --- | --- |
| `id` | Immutable job UUID returned by REST and used as the Kafka command correlation key. |
| `user_id` | External Care profile UUID owning the journal and result; not an authorization substitute. |
| `journal_entry_id` | Journal/AI-owned logical entry UUID being analyzed. |
| `journal_revision` | Positive immutable revision number ensuring results cannot be attached to edited text. |
| `provider` | Selected versioned execution provider for this reproducible run. Initial provider scope is OpenAI/Gemini; an optional PhoBERT value is valid only after ADR 0011's activation gate passes. |
| `prompt_version` | Exact prompt/input contract version used to interpret and validate the result. |
| `status` | Authoritative asynchronous job lifecycle used by REST polling and workers. |
| `attempt_count` | Number of claimed execution attempts used to enforce bounded retry. |
| `next_attempt_at` | UTC earliest retry instant; null when no retry is scheduled. |
| `error_code` | Stable safe failure classification; raw provider payloads and journal content are prohibited. |
| `result_document_id` | MongoDB result identifier for a validated completed analysis; null until available. |
| `requested_at` | UTC instant the user/system requested analysis. |
| `started_at` | UTC instant the current/first execution began; null before processing. |
| `completed_at` | UTC terminal completion instant; null while unfinished. |
| `created_at` | Immutable UTC database insertion instant. |
| `updated_at` | UTC instant of the latest job state, retry, or result-link change. |

### `ai.evaluation_dataset`

Governed metadata for a licensed, de-identified benchmark dataset stored privately.

| Field | Purpose |
| --- | --- |
| `id` | Immutable dataset UUID referenced by benchmark runs and sample documents. |
| `name` | Reviewed human-readable dataset identity unique with its version. |
| `version` | Source/content version distinguishing immutable dataset releases. |
| `source_url` | Optional provenance location for source and usage review, not a runtime download instruction. |
| `license_name` | Reviewed license/basis authorizing benchmark use. |
| `object_key` | Unique private object-storage key for the imported dataset artifact. |
| `checksum_sha256` | SHA-256 integrity digest proving the benchmark artifact has not changed. |
| `sample_count` | Validated non-negative number of imported samples for completeness checks. |
| `label_schema` | Versioned structured definition of expected labels and allowed values. |
| `preprocessing_version` | Exact deterministic preprocessing pipeline version required for reproducible evaluation. |
| `status` | Validation/publication lifecycle controlling whether benchmark runs may use the dataset. |
| `imported_by` | Authorized administrator account that initiated and attested the import. |
| `created_at` | Immutable UTC metadata insertion instant. |

### `ai.benchmark_run`

Reproducible execution and metrics for comparing configured models against one dataset.

| Field | Purpose |
| --- | --- |
| `id` | Immutable run UUID used by workers, predictions, and REST status polling. |
| `dataset_id` | Exact governed dataset version evaluated by this run. |
| `configuration` | Validated immutable model, prompt, thresholds, seed, and execution settings. |
| `status` | Authoritative asynchronous benchmark lifecycle. |
| `metrics` | Validated aggregate evaluation metrics; null until sufficient results exist. |
| `started_by` | Authorized research/admin account that requested the run. |
| `started_at` | UTC instant worker execution began; null while pending. |
| `completed_at` | UTC terminal completion instant; null while unfinished. |
| `created_at` | Immutable UTC request/insertion instant. |

## Conceptual owner `platform` (owner-local `public` tables)

### `platform.outbox_event`

Durable local transaction record awaiting publication to a Kafka topic.

| Field | Purpose |
| --- | --- |
| `id` | Immutable message UUID used for Kafka message identity and consumer deduplication. |
| `aggregate_type` | Stable owner aggregate category used for routing and diagnostics. |
| `aggregate_id` | Aggregate UUID used as the Kafka record key to preserve per-aggregate partition ordering. |
| `event_type` | Stable past-tense integration event or explicit asynchronous command name. |
| `schema_version` | Positive JSON contract version used by producers and consumers. |
| `correlation_id` | Workflow/trace grouping UUID propagated from the initiating request; not a business key. |
| `payload` | Validated minimized JSON payload; secrets and raw sensitive content are prohibited. |
| `occurred_at` | UTC instant the business fact committed, distinct from Kafka publication time. |
| `published_at` | UTC instant Kafka acknowledged publication; null while awaiting relay. |
| `attempt_count` | Number of relay publication attempts used for bounded retry and alerting. |
| `next_attempt_at` | UTC earliest next relay attempt after backoff; null for immediate eligibility. |
| `last_error_code` | Latest stable safe relay error category without broker payload or secret details. |

### `platform.audit_event`

Append-only minimized security/business audit fact for sensitive access and administrative changes.

| Field | Purpose |
| --- | --- |
| `id` | Immutable audit UUID used for export and integrity review. |
| `occurred_at` | UTC instant the audited action or decision occurred. |
| `actor_id` | Identity account UUID when a known user acted; null for anonymous/service actors. |
| `actor_type` | Actor category distinguishing account, service, and anonymous actions. |
| `action` | Stable machine-readable action that was attempted. |
| `resource_type` | Stable category of the resource affected or read. |
| `resource_id` | Opaque resource identifier retained for traceability across different identifier types. |
| `subject_user_id` | User whose sensitive data was affected, when different from the actor. |
| `consent_grant_id` | Exact consent grant used to authorize a sensitive action; null when not applicable. |
| `purpose` | Approved purpose for the sensitive access, minimized to avoid content disclosure. |
| `outcome` | Whether the attempt succeeded, was denied, or failed. |
| `correlation_id` | Trace/workflow UUID linking the audit fact to safe distributed diagnostics. |
| `ip_hash` | Privacy-minimized source IP hash used for anomaly investigation. |
| `metadata` | Allow-listed safe structured context; raw journals, chats, answers, tokens, and documents are prohibited. |

### `platform.moderation_case`

Governed workflow for reviewing a reported account, review, or chat message reference.

| Field | Purpose |
| --- | --- |
| `id` | Immutable case UUID exposed to authorized moderation APIs. |
| `reporter_id` | Identity account that submitted the report. |
| `target_type` | Category of referenced object determining the owning service and review policy. |
| `target_id` | Opaque target identifier; content is retrieved only through an authorized owner API. |
| `reason_code` | Stable report reason used for routing and aggregate reporting. |
| `description` | Optional reporter context, treated as untrusted sensitive user input. |
| `status` | Authoritative moderation workflow state. |
| `assigned_admin_id` | Administrator currently responsible for the case; null while unassigned. |
| `resolution_code` | Stable terminal action/reason code; null before resolution. |
| `resolution_note` | Authorized internal rationale minimized to required governance detail. |
| `created_at` | Immutable UTC report/case creation instant. |
| `resolved_at` | UTC instant a terminal resolution was recorded; null while open. |
| `updated_at` | UTC instant of the latest assignment, status, or resolution change. |

### `platform.retention_policy`

Versioned approved rule defining how long one data category may be retained.

| Field | Purpose |
| --- | --- |
| `id` | Immutable policy-row UUID used by deletion/audit workflows. |
| `data_category` | Stable owned data category to which the retention duration applies. |
| `policy_version` | Immutable reviewed policy revision unique within the data category. |
| `retention_days` | Positive maximum retention duration in calendar days. |
| `legal_basis` | Reviewed legal/policy justification for retaining this data category. |
| `active_from` | UTC instant this version begins governing new retention decisions. |
| `active_until` | Optional UTC instant this version stops applying; null while current. |
| `approved_by` | Authorized administrator account that approved the policy version. |
| `created_at` | Immutable UTC database insertion instant. |

### `platform.deletion_request`

User/account deletion workflow coordinating independent data owners without a distributed transaction.

| Field | Purpose |
| --- | --- |
| `id` | Immutable workflow UUID referenced by all deletion tasks. |
| `user_id` | Identity account whose owned data must be deleted or anonymized. |
| `status` | Aggregate deletion workflow state derived from owner task outcomes. |
| `requested_at` | UTC instant the valid deletion request was accepted. |
| `grace_expires_at` | Optional UTC instant after a documented cancellation grace period ends. |
| `started_at` | UTC instant fan-out processing began; null while pending. |
| `completed_at` | UTC instant all required owner tasks reached terminal success; null until complete. |
| `requested_by` | Identity account or authorized administrator that initiated the request. |
| `correlation_id` | Unique workflow correlation UUID used across Kafka commands, tasks, and audit. |
| `last_error_code` | Latest stable safe aggregate failure category; null when no failure is active. |

### `platform.deletion_task`

Idempotent per-data-owner work item belonging to one deletion request.

| Field | Purpose |
| --- | --- |
| `request_id` | Parent deletion workflow UUID. |
| `data_owner` | Stable service/owner name responsible for deleting its own stores and projections. |
| `status` | Authoritative owner-task state used to calculate aggregate request progress. |
| `attempt_count` | Number of processing attempts used to bound retries and alert operators. |
| `completed_at` | UTC instant this owner confirmed terminal completion; null while unfinished. |
| `last_error_code` | Latest stable safe owner failure category without deleted content. |
| `updated_at` | UTC instant of the latest task claim, retry, or status change. |
