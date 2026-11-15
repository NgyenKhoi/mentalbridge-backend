# Consultation PostgreSQL Field Data Dictionary

Owner: `consultation-service`. Runtime database: `mentalbridge_consultation`, default schema `public`.

The [owner migration history](../../../consultation-service/src/main/resources/db/changelog/) is executable runtime truth; the [service README](../../../consultation-service/README.md) describes its behavior. The [owner logical schema](../../domain-model/relational/consultation-service.sql) is read-only documentation and must never provision or migrate a database.

Original owner/conceptual section labels and section-specific status notes are retained below. Grouping a description here does not promote a conceptual or historical shape into an active migration.

[All PostgreSQL owners](../postgresql-field-data-dictionary.md)

## Conceptual owner `consultation` (`mentalbridge_consultation.public`)

### `consultation.specialist_profile`

Consultation-owned professional profile and authoritative administrator approval state. Private credential documents are stored separately and reviewed independently.

Database checks require a submitted timestamp once review starts, reviewer identity/time for terminal approval decisions, and a stable reason for rejection or suspension.

| Field | Purpose |
| --- | --- |
| `account_id` | External Identity account UUID and immutable specialist identifier; access still depends on current account state. |
| `display_name` | Reviewed specialist name displayed to users. |
| `biography` | Optional reviewed professional biography shown in discovery and booking. |
| `years_experience` | Non-negative stated professional experience used for informational filtering/display. |
| `timezone` | IANA timezone used to interpret and render the specialist schedule. |
| `approval_status` | Authoritative profile-review state controlling discovery, slots, appointments, and specialist access. |
| `submitted_at` | UTC instant the specialist submitted a complete profile for review; null before submission. |
| `reviewed_at` | UTC instant the latest administrator decision became effective; null until reviewed. |
| `reviewed_by` | External Identity administrator UUID responsible for the latest decision; null until reviewed. |
| `decision_reason_code` | Optional stable, non-sensitive rejection or suspension enum. MB-360 allows only the reviewed incomplete/content/scope rejection reasons and policy/quality/account-review suspension reasons; unrestricted evidence text is not stored. |
| `created_at` | Immutable UTC profile creation instant. |
| `updated_at` | UTC instant of the latest persisted profile or approval change. |
| `version` | Optimistic-lock counter preventing lost specialist-profile updates. |
| `published_version` | Monotonic approved-content version: initial approval/promotion increments it, draft edits and suspension/restoration do not. Zero means no approved snapshot yet. |

### `consultation.specialist_profile_support_area`

V1 reviewed non-clinical support domains declared by a specialist. The database
accepts only `DEPRESSIVE_SYMPTOMS` and `ANXIETY_SYMPTOMS`.

| Field | Purpose |
| --- | --- |
| `specialist_account_id` | Owning specialist profile and first half of the composite primary key. |
| `support_area` | Approved support-domain enum and second half of the composite primary key. |

### `consultation.specialist_profile_language`

Reviewed languages declared for user-facing support. Values are lowercase
`vi`, `en`, `zh`, and `fr`.

| Field | Purpose |
| --- | --- |
| `specialist_account_id` | Owning specialist profile and first half of the composite primary key. |
| `language_tag` | Supported language tag and second half of the composite primary key. |

### `consultation.specialist_credential_document`

Consultation-owned private credential metadata. Originals live in authenticated Cloudinary storage. Only a specialist owner or ADMIN can request a short-lived original-file link; only ADMIN can verify. Badge visibility never grants access to the original.

| Field | Purpose |
| --- | --- |
| `id` | Stable credential identifier for owner/admin review and optimistic commands. |
| `specialist_account_id` | Owning specialist profile; cascading deletion removes the metadata with the profile. |
| `title` / `issuer` / `issue_year` / `credential_type` | Specialist-supplied professional claim and ADMIN review context; public badge text is eligible only after verification. |
| `file_name` / `media_type` / `file_bytes` | Bounded original-file display and safety metadata, without storing the binary. |
| `storage_key` / `storage_format` | Private Cloudinary locator and format used only inside Consultation to issue an expiring URL. The key is unique and never public. |
| `verification_status` | `PENDING`, `VERIFIED`, or `REJECTED`; upload always begins pending. |
| `badge_visible` | Specialist choice; database permits true only for verified documents. |
| `reviewed_by` / `reviewed_at` | External ADMIN identity and UTC time of a terminal review decision; both null while pending. |
| `created_at` / `updated_at` | UTC creation and last review/visibility change. |
| `version` | Optimistic counter for review and badge commands. |

### `consultation.specialist_profile_status_history`

Append-only operational audit of explicit submission and administrator status
decisions. It stores no uploaded evidence or unrestricted notes.

| Field | Purpose |
| --- | --- |
| `id` | Immutable UUID audit-entry identifier. |
| `specialist_account_id` | Specialist profile whose state was submitted or changed. |
| `approval_status` | State established by this explicit action. |
| `reason_code` | Stable reviewed reason for an applied rejection or suspension; null for submit, resubmit, approval, and restoration. |
| `actor_account_id` | Identity UUID of the specialist or administrator performing the action. |
| `actor_role` | `SPECIALIST` or `ADMIN` operational actor class. |
| `occurred_at` | UTC instant when the action became effective. |

### `consultation.specialist_profile_approved_version`

MB-635 append-only approved six-field snapshots. Consultation is authoritative;
one baseline is captured for existing approved/suspended profiles, without
reconstructing unavailable old content. Profile snapshots contain professional
display data, never account credentials, verification files or health data.

| Field | Purpose |
| --- | --- |
| `id` | Immutable opaque snapshot identifier; migration baseline reuses the owner UUID. |
| `specialist_account_id` | Local specialist profile FK; paired with published_version for immutable content identity. |
| `published_version` | Positive per-specialist approved-content sequence, unique with specialist_account_id. |
| `profile_snapshot` | Exact approved JSON object with displayName, bio, supportAreas, languages, yearsOfExperience and timezone. Append-only; no amendment draft content leaks into discovery. |
| `approved_by` | External Identity UUID of the approving ADMIN; baseline uses available approval audit metadata. |
| `approved_at` | UTC effective approval time; baseline uses available approval metadata, not a fabricated historic content version. |
| `source_amendment_id` | Optional local amendment FK identifying promotion; null for initial/baseline approval. |

### `consultation.specialist_profile_amendment`

Separate private amendment to the approved public snapshot. At most one
DRAFT/PENDING_REVIEW/REJECTED row per specialist; approved and cancelled rows remain historical.
Only owner SPECIALIST and ADMIN can read it. Every command locks the owner profile
first and checks optimistic version; promotion additionally checks published base.

| Field | Purpose |
| --- | --- |
| `id` | Immutable opaque UUID used by owner/admin amendment APIs. |
| `specialist_account_id` | Local owning specialist profile FK; JWT subject determines owner access. |
| `base_published_version` | Exact approved content sequence used to derive the draft; composite FK to approved history prevents nonexistent bases. |
| `status` | DRAFT, PENDING_REVIEW, REJECTED, APPROVED or terminal CANCELLED amendment state; never replaces profile approval_status. Owner cancellation preserves the public profile and prior audit revisions, clears current submission/review metadata and frees the one-open-amendment constraint. |
| `proposed_profile` | Authoritative six-field proposed JSON payload, validated like the initial profile. It is private until reviewed promotion. |
| `submitted_at` | UTC latest explicit submission; null for DRAFT/CANCELLED, cleared on pending-review edits or cancellation. |
| `reviewed_at` | UTC latest ADMIN amendment decision; null before review, after resubmission or cancellation. Prior decisions remain in history. |
| `reviewed_by` | External Identity ADMIN UUID for latest decision, nullable before review, after resubmission or cancellation. |
| `reason_code` | Closed rejection reason only; retained while correcting REJECTED content, cleared on explicit resubmission or cancellation. No unrestricted notes. |
| `created_at` | Immutable UTC draft creation time, used with id to select latest owner amendment. |
| `updated_at` | UTC last persisted amendment change, distinct from public profile updated_at. |
| `version` | Optimistic-lock counter in amendment ETag; stale edits/submissions/decisions return 412. |

### `consultation.specialist_profile_amendment_history`

Append-only exact revision/provenance, including the payload ADMIN reviewed.
Later correction/resubmission never overwrites a rejected payload or actor/time.

| Field | Purpose |
| --- | --- |
| `id` | Immutable opaque history UUID. |
| `amendment_id` | Local amendment FK retaining the full audit chain. |
| `amendment_version` | Persisted revision corresponding to this history fact; unique with amendment_id. |
| `status` | Resulting amendment state for this revision. |
| `proposed_profile` | Exact six-field JSON payload of that revision, retained for approved/rejected review audit. |
| `actor_account_id` | External Identity UUID of the specialist editor/submitter or ADMIN reviewer. |
| `actor_role` | Bounded SPECIALIST/ADMIN actor class; no user identity content. |
| `reason_code` | Closed rejection reason for REJECTED facts, null otherwise. |
| `occurred_at` | UTC effective revision instant; ordering uses amendment_version, not wall-clock alone. |

### `consultation.subscription_plan`

Stable plan identity used to group immutable commercial versions.

| Field | Purpose |
| --- | --- |
| `code` | Stable authoritative catalogue code: `FREE`, `PLUS`, or `PREMIUM`. |
| `display_name` | Reviewed user-facing plan name. |
| `sort_order` | Unique presentation and upgrade ordering; zero is `FREE`, followed by `PLUS` and `PREMIUM`. |

### `consultation.current_service_entitlement`

Authoritative current-package read model used by cross-service capability and
model-routing decisions. It is deliberately not a payment, subscription,
billing-period, consultation-credit, or ledger aggregate. Absence of an
effective row means `FREE` with synthetic `DEFAULT_FREE` provenance. Stored
rows are only explicit bounded `DEMO` or future billing-produced `PAID`
projections; an expired row is ignored rather than silently extending access.

| Field | Purpose |
| --- | --- |
| `account_id` | External Identity user UUID and primary key for the single current projection; it is never accepted from an AI client as a routing claim. |
| `package_code` | Current paid/demo package, restricted to `PLUS` or `PREMIUM`; `FREE` is represented by no effective row. |
| `source` | Provenance class `DEMO` or future `PAID`; it prevents controlled-demo access from masquerading as payment. |
| `source_reference` | Stable non-blank demo identifier or future billing lifecycle reference supporting operational traceability. |
| `established_by` | External Identity administrator UUID required for `DEMO`; future automated `PAID` projections may leave it null while retaining their billing reference. |
| `effective_from` | Inclusive UTC instant from which the projection may authorize package-specific behavior. |
| `effective_until` | Exclusive UTC instant after which lookup returns `FREE` unless a new effective projection exists. |
| `policy_version` | Exact entitlement-read policy; MB-369 fixes `service-entitlement-v1`. |
| `created_at` | Immutable UTC insertion instant for this current projection row. |
| `updated_at` | UTC instant of the latest authoritative projection replacement. |
| `version` | Optimistic-lock counter reserved for safe future demo/billing projection updates. |

### `consultation.service_credit_period`

Implemented MB-377/MB-558 allocation boundary. One row freezes the highest package allocation seen for an exact user, entitlement policy version, credit policy version, and effective period. `DEMO` and `PAID` provenance is immutable within that period; an existing v1 period is never expanded to v2 quantities.

| Field | Purpose |
| --- | --- |
| `id` | Server-generated period UUID. |
| `account_id` | External Identity user UUID that owns every credit in the period. |
| `plan_version` | Exact purchased plan-version UUID text for new billing periods, resolved from the matching successful payment and period start. Legacy/demo periods retain their original entitlement-policy label; reload never re-provisions them under a new identifier. |
| `credit_policy_version` | Exact allocation policy frozen at period creation: historical `consultation-credit-v1` or current `consultation-credit-v2`. |
| `package_code` | Highest allocated package in the period: `PLUS` or `PREMIUM`. |
| `source` | Explicit `DEMO` or `PAID` provenance; never inferred by the client. |
| `source_reference` | Stable entitlement lifecycle or controlled-demo reference. |
| `period_start` / `period_end` | Inclusive/exclusive UTC billing or demo window. |
| `allocated_count` | Frozen allocation after an allowed in-period upgrade: v1 stores Plus 1 or Premium 3; v2 stores Plus 4 or Premium 10. A renewal creates a separate period and never adds unused prior credits. |
| `created_at` / `updated_at` | UTC creation and latest in-period upgrade instants. |
| `version` | Optimistic version incremented by allocation upgrade. |

### `consultation.service_credit`

One indivisible consultation right. Current state is owner-controlled; balances are counted from these rows and never reconstructed in the browser.

| Field | Purpose |
| --- | --- |
| `id` | Stable credit UUID. |
| `period_id` | Owning immutable-provenance service-credit period. |
| `ordinal` | One-based position within the policy-versioned allocation; unique per period and capped at ten. |
| `state` | `AVAILABLE`, appointment `HELD`, upgrade `UPGRADE_HELD`, `CONSUMED`, `FORFEITED`, period `EXPIRED`, or upgrade-applied `REVOKED`. A release returns the state to `AVAILABLE` while the ledger preserves the fact. |
| `appointment_id` | Local appointment UUID required only for appointment-held and appointment-terminal outcomes; upgrade-held/expired/revoked credits keep it null. |
| `created_at` / `updated_at` | UTC creation and latest transition instants. |
| `version` | Optimistic transition counter. |

### `consultation.service_credit_ledger`

Append-only evidence for provisioning and appointment-driven transitions.

| Field | Purpose |
| --- | --- |
| `id` | Immutable event UUID. |
| `credit_id` | Credit whose state changed. |
| `account_id` | Denormalized owner UUID for bounded history and account-scoped idempotency. |
| `event_type` | Stable provisioning, booking, upgrade hold/release, consumption, expiry, forfeiture, revocation, or explicit post-settlement adjustment fact. The ledger never represents a refund. |
| `appointment_id` | Appointment correlation for booking/session transitions; null for provisioning and subscription-upgrade transitions. |
| `idempotency_key` | Owner command key unique per account; exact replay does not append another event. |
| `occurred_at` | Immutable server UTC transition instant. |

### `consultation.appointment`

Implemented MB-378/MB-558 request aggregate with MB-379 decision settlement, MB-380 owner cancellation/reschedule audit, and MB-383 evidence-backed chat settlement. One row is the immutable scheduling snapshot created while the same local transaction locks the exact availability slot, holds one eligible credit, and enforces the package reservation cap. New requests support only in-app chat or gated in-app video.

| Field | Purpose |
| --- | --- |
| `id` | Server-generated appointment UUID and local credit-ledger correlation. |
| `user_account_id` | External Identity USER UUID that owns and may reload the request. |
| `specialist_account_id` | Approved specialist snapshotted from the selected slot. |
| `availability_slot_id` | Exact published 60-minute slot; a partial unique index permits at most one active request/confirmation. |
| `service_credit_id` | Earliest-expiring available credit that covers the appointment start; unique while the appointment is active. |
| `status` | Initial `REQUESTED`; active reservations are `REQUESTED`, `CONFIRMED`, or `IN_PROGRESS`; channel end records `SESSION_ENDED`; only evidence-backed completion advances to `COMPLETED`; decision/cancellation outcomes may be `REJECTED`, `EXPIRED`, or `CANCELLED`. |
| `modality` | `IN_APP_CHAT` or `IN_APP_VIDEO`; physical, phone, and external-link modes are not accepted. |
| `scheduled_start_at` / `scheduled_end_at` | Immutable exact UTC interval copied from availability and constrained to 60 minutes. |
| `display_timezone` | IANA timezone copied from the slot for stable user display. |
| `requested_at` | Server UTC command instant used for unexpired-window validation and deadline calculation. |
| `decision_deadline_at` | Earlier of 24 hours after request or two hours before start when that cutoff is still future; otherwise the unexpired slot end. Always after the request and no later than scheduled end. The decision/expiry owner consumes this handoff. |
| `idempotency_key` | Printable user-scoped request key; exact retry returns this row and conflicting reuse fails. |
| `replaces_appointment_id` | Optional self-reference to the eligible future appointment replaced by this request. The old immutable schedule is retained as `CANCELLED`; its reservation capacity and, when policy permits, exact held credit move to the replacement atomically. One old appointment may be replaced only once. |
| `originating_plan_action_id` | Optional opaque Care `PROFESSIONAL_SUPPORT` action reference supplied only by an explicit owner booking flow. A replacement inherits the owned prior appointment's value when the request omits it and rejects a conflicting explicit value. It has no cross-service foreign key, grants no data access, never authorizes Consultation to mutate SupportPlan lifecycle, and supports the owner-and-action appointment-history query. |
| `decided_at` | Server UTC instant of specialist acceptance/rejection or deterministic deadline expiry; null until a request decision occurs. |
| `decision_reason` | Stable `SPECIALIST_ACCEPTED`, `SPECIALIST_REJECTED`, or `DECISION_DEADLINE_EXPIRED` outcome paired with `decided_at`. |
| `cancellation_reason` | Stable reason required only for `CANCELLED`: `USER_CANCELLED`, `USER_RESCHEDULED`, or `SPECIALIST_SUSPENDED`. |
| `cancelled_at` | Server UTC cancellation instant required only for `CANCELLED`. |
| `cancelled_by` | Identity actor UUID required only for `CANCELLED`; owner commands record the user and suspension records the administrator. |
| `cancellation_credit_outcome` | Immutable `RELEASED`, `FORFEITED`, or `TRANSFERRED_TO_REPLACEMENT` result required only for `CANCELLED`. It describes this appointment's settlement even when the shared credit is currently held by its replacement. |
| `session_outcome` / `session_outcome_reason` | Separate final chat result and stable reason: completion, either/both no-show, insufficient evidence, or timed-out evidence review. Video records only verified COMPLETED; unverified video retains null outcome/reason with HELD credit. |
| `session_policy_version` | `chat-session-completion-v1` for chat; `livekit-video-completion-v1` for video. Video end alone leaves outcome/fact/settlement null and credit HELD. |
| `session_ended_at` / `session_settled_at` | Server instants for history-only channel end and later outcome/credit settlement. End alone never consumes a credit. |
| `evidence_review_started_at` / `evidence_failure_reason` | Minimized technical-reconciliation state. The end-plus-35-minute fallback is historical chat policy only. Video flags unresolved manual review after 24 hours and remains HELD; no automatic release/settlement is approved. |
| `completion_fact_id` | Opaque unique fact present only for evidence-backed `COMPLETED`; MB-516 uses it as a unique earning input. Timer-only session end still creates no earning. |
| `created_at` / `updated_at` | UTC insertion and latest authoritative state-change instants. |
| `version` | Optimistic state-transition counter for later decision commands. |

### `consultation.appointment_status_history`

Append-only transition evidence introduced by MB-360, extended by MB-379 for idempotent decisions/expiry, and completed by MB-380 for initial request plus cancellation/reschedule settlement history.

| Field | Purpose |
| --- | --- |
| `id` | Immutable transition UUID. |
| `appointment_id` | Appointment whose authoritative status changed. |
| `from_status` / `to_status` | Valid current appointment states before and after the transition, including the shared `IN_PROGRESS` state used by the credit/reschedule lifecycle. |
| `changed_by` | Identity actor UUID responsible for the transition. |
| `reason` | Stable reviewed outcome reason without private consultation content. |
| `idempotency_key` | Optional printable command key. MB-379 specialist decisions and deterministic expiry use one key per appointment command; a partial unique index prevents duplicate evidence. |
| `credit_outcome` | Null for non-cancellation transitions; immutable `RELEASED`, `FORFEITED`, or `TRANSFERRED_TO_REPLACEMENT` for a transition into `CANCELLED`. |
| `changed_at` | Server UTC instant at which the transition committed. |

### `consultation.appointment_video_room`

MB-384 owner mapping: one opaque provider room per appointment. Never contains
secrets, media, private consultation content or client-selected authority.

| Field | Purpose |
| --- | --- |
| `appointment_id` | Local one-to-one owner reference and primary key; serializes access/closure with the appointment lock. |
| `room_name` | Unique opaque UUID-based Cloud room, stable across reconnects; no business/person identifiers. |
| `user_identity` / `specialist_identity` | Distinct random provider identities, mapped server-side to assigned roles. |
| `state` | PREPARING, OPEN, CLOSING, CLOSED or privacy PURGED tombstone; cannot authorize joining an ended appointment. |
| `failure_reason` | Bounded technical uncertainty, not clinical content or provider error payload. |
| `next_close_at` | Durable bounded retry/claim time for revocation and room deletion; never extends the appointment. |
| `next_verification_at` | Fair bounded reconciliation retry cursor; updates provider-room version, not the Appointment ETag on every unsuccessful poll. |
| `closed_at` | Server acknowledgement of successful revocation/deletion, not appointment completion. |
| `created_at` | Server mapping instant. |
| `retention_hold_reason` | Optional bounded operator legal/dispute/valid retention hold code; no free-text health information. |
| `version` | Optimistic concurrency counter, in addition to owner appointment locking. |

### `consultation.appointment_video_confirmation`

Immutable business confirmation audit, distinct from expiring participation
metadata. Never removed by the LiveKit retention job; no video/content is stored.

| Field | Purpose |
| --- | --- |
| `appointment_id` / `id` | One intent per owner appointment; confirmation UUID has a composite owner FK from Appointment.video_confirmation_id, preventing cross-appointment provenance. |
| `confirmed_by` | Assigned currently approved Specialist's authenticated Identity subject, not a request field. |
| `source_version` | Strong Appointment version observed before the accepted command. |
| `idempotency_key` | Printable 16–128 character command key, unique per Specialist; replays recover the same intent even with an old If-Match. |
| `accepted_at` | Server UTC assertion time; may precede scheduled end. |
| `policy_version` | Immutable livekit-video-completion-v1 provenance, distinct from historical chat. |

`consultation.appointment.video_confirmation_id` is nullable for historical
chat and non-confirmed video. A video COMPLETED row requires an owner-linked
confirmation, immutable completion fact, verified outcome, actual end within the
scheduled interval and settlement not earlier than actual end. Review flags
remain non-terminal SESSION_ENDED with null settledAt/outcome and credit HELD.

### `consultation.appointment_video_event`

MB-384 minimized signature-verified LiveKit participation metadata, never raw
webhook bodies, JWTs, recording, audio/video or consultation content. A source
event ID is globally unique. The owner appointment lock serializes insertion,
duplicate/conflict handling, verification and retention.

| Field | Purpose |
| --- | --- |
| `event_id` | Bounded provider idempotency identity; identical replay does not create new evidence. Conflicting replay flags uncertainty rather than overwriting. |
| `appointment_id` | Owner-local room/appointment reference; no client-authored assignment. |
| `event_type` | Only participant_joined, participant_left and room_finished. |
| `room_sid` | Provider room incarnation; pairing/overlap cannot mix unrelated room incarnations. |
| `participant_identity` / `participant_sid` | Opaque assigned identity and connection SID; nullable only for room_finished. Reconnect uses separate pairs. |
| `participant_role` | USER/SPECIALIST derived from the private room mapping, not provider metadata claims; null for room_finished. |
| `occurred_at` | Signature-verified source UTC occurrence; validity/overlap is bounded by the authoritative appointment window. |
| `received_at` | Server intake UTC instant; retention never removes a recent delayed delivery. |

Cleanup deletes evidence and replaces room/participant identifiers with privacy
tombstones after both closure and latest intake are older than 90 days. Any
appointment dispute or bounded operator retention hold prevents cleanup. Purged
room callbacks cannot recreate evidence. Appointment, status history, Brief,
Summary, completion facts and financial rows are not deleted by this job.

### `consultation.appointment_chat_evidence`

Content-free, append-only evidence received from Realtime for an assigned
participant. Subscribe/join is deliberately absent and raw chat content is
never stored.

| Field | Purpose |
| --- | --- |
| `id` | Internal immutable evidence-row UUID. |
| `appointment_id` / `evidence_id` | Owner-local appointment and source idempotency identity; the pair is unique. |
| `participant_account_id` / `participant_role` | Server-authenticated assigned `USER` or `SPECIALIST`; never accepted from a client claim. |
| `evidence_type` | `CHECK_IN`, `PRESENCE_INTERVAL`, or `ACCEPTED_MESSAGE`. |
| `interval_started_at` | Required only for a server-observed presence interval, capped at 60 seconds per fact. |
| `message_id` | Realtime-owned accepted-message UUID required only for message evidence; it is not message content. |
| `occurred_at` | Server-observed occurrence used for the half-open appointment-window calculation. |
| `received_at` | Consultation receipt time used to enforce grace and reconciliation deadlines. |

### `consultation.appointment_dispute`

MB-619's one-per-appointment dispute aggregate. It stores only operational reason/provenance and bounded evidence metadata; raw chat, ConsultationBrief, Journal, assessment, recording, private notes, and clinical conclusions are prohibited.

| Field | Purpose |
| --- | --- |
| `id` / `appointment_id` | Stable dispute identity and unique contested appointment. |
| `appointment_version` | Exact appointment version observed when the dispute opened. |
| `opened_by_account_id` / `opened_by_role` | Server-authenticated participant provenance; role is `USER` or assigned `SPECIALIST`. |
| `reason_code` | Stable bounded operational reason; never free-text session content. |
| `evidence_type` / `evidence_occurred_at` | Optional minimized operational metadata identifying an access, connection, or provider fact and its occurrence time. |
| `opened_at` / `eligible_until` | Server timestamps proving the command was accepted inside the 24-hour post-settlement window. |
| `status` | `OPEN` gates future earning eligibility; `RESOLVED` is immutable. |
| `resolution_outcome` / `resolution_reason` | Bounded admin decision and stable non-clinical rationale. |
| `resolved_by` / `resolved_at` | Resolving administrator and server audit time. |
| `prior_appointment_status` / `prior_session_outcome` | Exact immutable source facts observed during resolution. |
| `resulting_appointment_status` / `resulting_session_outcome` | Explicit resulting facts; MB-619 does not silently rewrite them. |
| `credit_action` | Exact `NONE`, `ALREADY_AVAILABLE`, or `ADJUSTED_RELEASED` result. |
| `open_idempotency_key` / `resolution_idempotency_key` | Stable command identities for replay-safe open and resolution. |
| `created_at` / `updated_at` / `version` | UTC persistence timestamps and optimistic resolution version. |

### `consultation.specialist_client_continuity_audit`

Content-free MB-592 evidence that an authenticated specialist attempted to load
the bounded current/recent client relationship projection. It records neither
client identifiers nor health, assessment, journal, chat, or summary content.

| Field | Purpose |
| --- | --- |
| `id` | Immutable audit-event UUID. |
| `specialist_account_id` | Identity specialist UUID whose current approval and appointment relationships were evaluated. |
| `action` | Bounded operation name; MB-592 permits only `LIST`. |
| `outcome` | `ALLOWED` when the bounded projection was returned or `DENIED` when current specialist eligibility failed closed. |
| `reason_code` | Stable content-free authorization result used for privacy review and operational investigation. |
| `relationship_count` | Number of relationship rows returned; always zero for a denied attempt and never identifies a client. |
| `occurred_at` | Immutable server UTC instant at which Consultation evaluated the request. |

### `consultation.session_summary`

Append-only MB-385 snapshot published by the assigned specialist only after the appointment has evidence-backed `COMPLETED` authority. Amendments create a new row and never rewrite the prior user-visible version.

| Field | Purpose |
| --- | --- |
| `id` | Immutable summary-snapshot UUID. |
| `appointment_id` | Completed owner appointment; version numbering is scoped to this appointment. |
| `user_account_id` / `specialist_account_id` | Denormalized user owner and publishing specialist provenance. |
| `summary_version` / `schema_version` | Monotonic appointment version and bounded `session-summary-v1` payload contract. |
| `topics_discussed` | One to eight short, user-visible, non-diagnostic topics. |
| `progress_summary` / `specialist_note_for_user` | Optional bounded user-visible text; private notes, raw journals, assessment answers, and chat transcript are excluded. |
| `follow_up_suggested` | Non-binding indication that another appointment may be useful. |
| `amends_summary_id` | Prior immutable snapshot corrected by this version; null only for version one. |
| `idempotency_key` / `request_hash` | Specialist-scoped command replay and conflicting-reuse evidence. |
| `published_at` | Immutable server publication instant. |

### `consultation.agreed_next_step`

Immutable steps agreed in one published summary. They are user-visible suggestions and never become Care SupportPlan occurrences automatically.

| Field | Purpose |
| --- | --- |
| `id` / `summary_id` / `ordinal` | Stable step identity and display order within an immutable summary version. |
| `step_type` | Bounded checklist, journal, emotion check-in, reassessment, follow-up appointment, or platform resource type. |
| `title` / `details` | Bounded user-visible action wording. |
| `resource_id` / `resource_version` | Exact Content resource proposal required only for `PLATFORM_RESOURCE`; Care revalidates any later plan change under MB-560. |
| `resource_proposal_reason_code` | Required bounded rationale for `PLATFORM_RESOURCE`: post-consultation continuity, an alternative resource, or an addressed reported barrier; null for every other step type. |
| `created_at` | Server creation instant inherited from summary publication. |

### `consultation.agreed_next_step_state`

Mutable checklist projection controlled only by the user. It is intentionally excluded from specialist reads and is not clinical monitoring.

| Field | Purpose |
| --- | --- |
| `next_step_id` / `user_account_id` | One state row for the owning user's immutable agreed step. |
| `state` | User-selected `PENDING`, `COMPLETED`, or `SKIPPED`. |
| `hidden` | User-controlled visibility in their own checklist. |
| `version` / `updated_at` | Optimistic concurrency and latest user action instant. |

### `consultation.session_summary_reuse_consent`

Separate user consent for future reuse of one exact summary snapshot. Appointment-preparation sharing does not imply this consent, and revocation blocks later handoff reads.

| Field | Purpose |
| --- | --- |
| `summary_id` / `user_account_id` | Exact immutable snapshot and owning user. |
| `approved` | Current user decision for future ConsultationBrief reuse. |
| `version` / `updated_at` | Optimistic concurrency and latest decision instant. |
| `approved_at` / `revoked_at` | Auditable approval or latest revocation instant without copying the summary. |

### `consultation.appointment_outbox_event`

Transactional handoff of one minimized appointment lifecycle version to the
Content/Notification reminder consumer. The row commits with the appointment
transition and excludes email addresses and consultation content.

| Field | Purpose |
| --- | --- |
| `id` | Stable Kafka `messageId` reused across relay attempts. |
| `appointment_id`, `appointment_version` | Unique aggregate/version identity preserving per-appointment order and dedupe. |
| `correlation_id` | Safe request/job correlation UUID. |
| `payload` | Minimized user/specialist owner IDs, status, UTC start, modality, and replacement link only. It excludes Journal, assessment, consultation brief, session summary, and chat bodies. |
| `occurred_at` | Authoritative transition instant. |
| `published_at` | Kafka acknowledgement instant; null while pending. |
| `attempt_count`, `next_attempt_at` | Bounded observable relay retry/lease state. |
| `created_at` | Immutable insertion instant. |

### `consultation.consultation_admin_audit_outbox`

Consultation-owned durable transactional outbox for administrator actions (specialist profile approve, reject, suspend, restore). The row commits in the same local transaction as the profile and status history update, and is relayed to Kafka topic `mentalbridge.admin.audit-event.v1`.

| Field | Purpose |
| --- | --- |
| `id` | Immutable event UUID matching the `eventId` in the audit event fact. |
| `deduplication_key` | Stable unique command idempotency key preventing duplicate outbox entries on retried actions. |
| `event_type` | Stable language-neutral event type (e.g. `consultation.specialist.approved`). |
| `correlation_id` | End-to-end trace correlation UUID passed from caller ingress. |
| `target_account_id` | Nullable specialist account UUID subject of the administrative action. |
| `payload` | Strict minimized `administration-audit-event-v1` JSON object; excludes clinical notes and sensitive credentials. |
| `occurred_at` | Authoritative UTC timestamp of the administrative decision. |
| `published_at` | Nullable UTC instant when published and acknowledged by Kafka; null while pending. |
| `attempt_count` | Non-negative relay retry counter for observable bounded backoff. |
| `next_attempt_at` | Scheduled UTC instant for the next retry attempt when relay fails. |
| `created_at` | Immutable row creation timestamp. |

### `consultation.subscription_plan_version`

Immutable price, allocation, credit, revenue-share, and cancellation policy purchased by a subscription period.

| Field | Purpose |
| --- | --- |
| `id` | Immutable plan-version UUID referenced by subscriptions, upgrades, payments, and credits. |
| `plan_code` | Stable parent plan identity. |
| `version` | Immutable version within the plan; published versions are never rewritten. |
| `currency` | Contract-fixed `VND` currency for every amount. |
| `price_minor` | Immutable monthly VND price. New MB-622 sandbox versions use PLUS 5,000/PREMIUM 10,000; retired v2 versions retain 1,390,000/3,490,000 without historical rewriting. |
| `pricing_policy_version` | Immutable rule: ordinary/historical `upgrade-offset-v1` or `sandbox-full-price-v1`; sandbox upgrade applies zero offset. Pricing alone never proves payout funding; only the separate MB-812 verified-funded earning snapshot permits staging PAYOS redemption. |
| `billing_period_months` | Calendar-month period count; MB-515 supports exactly one month. |
| `consultation_credits_per_period` | Credits granted for the active period: FREE 0, PLUS 4, or PREMIUM 10; unused credits never roll over. |
| `max_active_reservations` | Frozen active appointment cap: FREE 0, PLUS 2, or PREMIUM 4. |
| `non_consultation_value_minor` | Versioned price allocation for non-consultation features, used for upgrade calculation without runtime FX. |
| `credit_value_minor` | Fixed per-credit allocation; paid v2 versions store 300,000 VND so earnings are not derived from the whole package. |
| `specialist_share_bps` | Specialist share in basis points snapshotted into each credit; 7000 means 70%. |
| `ai_quota_code` | Stable quota/configuration code frozen with the purchased plan version. |
| `support_plan_enabled` | Frozen plan decision controlling SupportPlan entitlement for the period. |
| `effective_from` | UTC instant at which the version may be offered to new purchases. |
| `retired_at` | Optional UTC instant after which new purchases cannot select the version; historical records remain valid. |
| `created_at` | Immutable UTC insertion instant. |

### `consultation.subscription_plan_entitlement`

Allow-list of feature decisions attached to one immutable plan version.

| Field | Purpose |
| --- | --- |
| `plan_version_id` | Plan version that authoritatively includes the capability. |
| `entitlement_code` | Stable capability code returned through narrow current-entitlement decisions; safety guidance is never gated here. |

### `consultation.user_subscription`

Authoritative paid-subscription lifecycle for one user. Absence of an active
paid row means `FREE` entitlement. MB-515 exposes new purchase and
`PLUS`-to-`PREMIUM` upgrade only, with no downgrade/refund API.

| Field | Purpose |
| --- | --- |
| `id` | Immutable subscription UUID used by payment, upgrade, status, and audit records. |
| `user_id` | External Identity user UUID owning the subscription; not proof of current request authorization. |
| `plan_version_id` | Current immutable paid-plan version; an applied upgrade changes this reference while upgrade history preserves the prior version. |
| `status` | Current lifecycle state: `PENDING_PAYMENT`, `ACTIVE`, or terminal `ENDED`. |
| `current_period_start` | UTC start of the active provider billing period; null while initial payment is pending. |
| `current_period_end` | UTC exclusive end of the current billing period used for benefit/credit expiry and exact upgrade seconds. |
| `ended_at` | UTC instant the paid subscription ceased being current: the period end when an elapsed `ACTIVE` row is closed on checkout, or the failure instant for a pending purchase; null while active/pending. |
| `idempotency_key` | User-scoped creation retry key returning the original subscription outcome. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest lifecycle, period, or plan change. |
| `version` | Optimistic-lock counter protecting renewal, cancellation, and upgrade races. |

### `consultation.subscription_status_history`

Append-only timeline of subscription status transitions.

| Field | Purpose |
| --- | --- |
| `id` | Immutable transition UUID. |
| `subscription_id` | Subscription whose authoritative status changed. |
| `from_status` | Previous status; null only for initial creation. |
| `to_status` | New validated status. |
| `reason_code` | Required stable machine-readable reason without provider payload text. |
| `changed_at` | UTC instant the transition committed. |

### `consultation.payment_transaction`

Provider-snapshotted payment attempt and current externally confirmed outcome.
PayOS is the active collect adapter on staging only; MoMo data/IPN support stays
dormant and retains its original provider. `FAKE` exercises simulated QR checkout
in local/CI. The additive MB-786 migration preserves historical rows and nullable
legacy terminal reasons; no refund transaction or production enablement is added.
`payment_order_code_seq` reserves positive numeric checkout order codes through
9,007,199,254,740,991 without cycling; a reserved code is never regenerated on
command replay. Exact provider/order identity, PayOS link identity and settled
PayOS transaction references are unique. A pending provider-create timeout keeps
nullable QR/link metadata and cannot authorize another charge attempt.

| Field | Purpose |
| --- | --- |
| `id` | Immutable payment UUID exposed in bounded payment history. |
| `subscription_id` | Paid subscription receiving an initial purchase or upgrade. |
| `target_plan_version_id` | Exact immutable plan version whose price and rights were used to create this attempt. |
| `payment_provider` | Immutable `PAYOS`, `MOMO` or `FAKE` namespace. PAYOS real collection is staging-only under ADR 0047; switching configuration never relabels or resends an existing attempt. |
| `momo_order_id` | Required merchant-generated MoMo/legacy FAKE `orderId`, unique per provider and the IPN correlation key; null for PAYOS. |
| `momo_request_id` | Required merchant-generated MoMo/legacy FAKE `requestId`, unique per provider and matched exactly on IPN; null for PAYOS. |
| `momo_trans_id` | Positive MoMo transaction ID when issued. Zero/unusable values are not copied from a failed IPN; positive values are unique. |
| `order_code` | Sequence-reserved positive integer within the SDK safe-integer range; required for PAYOS and used by new FAKE QR checkout, nullable on legacy/MOMO rows. Unique within the immutable provider namespace. |
| `payos_payment_link_id` | Nullable opaque PayOS link identity confirmed by SDK create/query, then matched exactly during settlement. Unique across payments; absent until a provider-create response is confirmed. |
| `payos_reference` | Nullable exact authenticated successful transaction reference. Unique across payments so a reused reference cannot grant rights twice; other signed occurrences remain reviewable receipts. |
| `amount_minor` | Exact positive amount requested/confirmed in currency minor units. |
| `currency` | ISO 4217 currency validated against the plan/upgrade quote. |
| `status` | `PENDING`, `SUCCEEDED`, `FAILED`, or externally reported `CHARGEBACK`; refund states are unsupported. |
| `purpose` | Distinguishes historical purposes. New v2 user-facing operations create only purchase or upgrade attempts; no downgrade/refund purpose exists. |
| `momo_result_code` | Latest verified MoMo `resultCode`; only final code `0` can produce `SUCCEEDED`. |
| `momo_pay_type` | Verified MoMo `payType`, such as QR or app payment. |
| `idempotency_key` | Subscription-scoped payment retry key preventing duplicate attempts. |
| `momo_response_time_epoch_ms` | Exact verified MoMo millisecond timestamp retained for signature/reconciliation evidence. |
| `provider_occurred_at` | UTC occurrence instant parsed from verified provider evidence; null while checkout is pending or no provider instant was supplied. |
| `paid_at` | UTC instant Consultation accepted verified provider success and settled the payment; null until successful. It is not necessarily the provider's original transfer time. |
| `failed_at` | UTC terminal failure instant; null unless failed. |
| `expires_at` | Local UTC checkout/link deadline. It stops unverified attempts and non-PayOS settlement; an exactly matched verified PayOS `PAID` result can still settle a `PENDING` payment when evidence arrives after this deadline. |
| `checkout_url` | Nullable safe provider redirect. Null while durable checkout preparation is awaiting an unconfirmed provider response (including timeout); no redirect or automatic charge resend. Verified callback may finalize the payment even when null. Historical URLs are unchanged. Never authorization evidence. |
| `qr_code_url` | Optional safe MoMo QR URL returned with checkout. |
| `qr_code` | Nullable provider VietQR payload, or deterministic FAKE payload, rendered inside the application; never treated as a hosted URL. Missing for legacy/non-QR/unconfirmed attempts. |
| `bank_bin` | Nullable destination bank BIN returned with confirmed QR details. Stored to reproduce transfer instructions, not a user's bank identity. |
| `bank_account_number` | Nullable merchant collection account returned with confirmed QR details; exposed only with the owner's payment and never copied from a payer's transaction details. |
| `bank_account_name` | Nullable merchant account-holder display text returned with confirmed QR details; no payer name is retained. |
| `payment_description` | Nullable provider transfer description of at most nine characters, with no patient content. Persisted with QR details for exact instruction replay. |
| `terminal_reason` | Nullable server-authoritative `EXPIRED`, `CANCELLED` or `PROVIDER_FAILED` supplementing ledger status FAILED; legacy rows may stay null. Never inferred from browser return/cancel URLs; committed terminal payments never reopen. |
| `reconciliation_next_at` | Nullable UTC earliest next SDK status query or expiry-cancellation retry, atomically claimed to bound polling. Local expiry commits pending cancellation and its 30-second retry deadline together, so a process stop before or during provider cancellation remains recoverable. Null means no cadence has been scheduled or cancellation completed. Normal queries use a 30-second cadence; failures back off to at most 300 seconds. Provider HTTP runs after the claim commits. |
| `reconciliation_failures` | Non-negative consecutive provider-query or expiry-cancellation failure counter used for bounded backoff. A local transition to EXPIRED resets the counter before its first cancel attempt, giving cancellation a separate six-attempt budget. Success resets it; six failed cancel attempts require administrator review and stop automatic retries. |
| `reconciliation_failure_code` | Nullable stable safe query/cancellation code: PAYOS_STATUS_UNAVAILABLE preserves pending uncertainty; PAYOS_CANCEL_PENDING is durable provider-cancellation intent committed atomically with local expiry; PAYOS_CANCEL_UNAVAILABLE retains that intent after a caught provider failure; PAYOS_CANCEL_REQUIRES_REVIEW marks exhaustion of six cancel attempts. The scheduler consumes pending/unavailable intent even after a process restart; successful cancellation clears it and its retry deadline. Query results cannot clear concurrent cancellation intent. No provider response body, account information or credentials is stored. |
| `created_at` | Immutable UTC attempt creation instant. |
| `updated_at` | UTC instant of the latest verified payment-state change. |
| `version` | Optimistic-lock counter preventing conflicting webhook/status application. |

### `consultation.momo_payment_ipn`

Verified, deduplicated receipt of the selected MoMo One-Time Payment v2 IPN contract. Invalid signatures/malformed payloads produce bounded metrics and safe diagnostics but do not persist attacker-controlled fields. Raw payload, signature, `orderInfo`, and `extraData` are discarded after hashing.

| Field | Purpose |
| --- | --- |
| `id` | Internal immutable receipt UUID. |
| `payment_id` | Matched local payment after signature and partner/order/request/amount validation; null for a verified but unknown order. |
| `contract_version` | MoMo DTO/signature-policy version defining required/optional fields and result handling. |
| `deduplication_key` | SHA-256 key derived from contract version plus verified `partnerCode|orderId|requestId|transId|resultCode|responseTime`. |
| `partner_code` | Verified merchant `partnerCode`, matched to environment configuration. |
| `order_id` | Verified MoMo `orderId`, matched to local `momo_order_id`. |
| `request_id` | Verified MoMo `requestId`, matched to local `momo_request_id`. |
| `amount_minor` | Verified VND amount; MoMo VND has no fractional minor digits. |
| `currency` | Contract-fixed `VND`; a compatible VND plan version is required before real payment. |
| `order_info_sha256` | Hash of required signed `orderInfo`; raw provider text is not persisted. |
| `order_type` | Verified MoMo order type included in the canonical signature. |
| `trans_id` | Exact verified MoMo `transId`, including provider sentinel values needed for receipt evidence. |
| `result_code` | Verified MoMo result code. Only final `0` may activate subscription. |
| `result_message` | Bounded provider-owned description associated with the result code; never used to decide status. |
| `pay_type` | Verified MoMo payment channel included in the signature. |
| `response_time_epoch_ms` | Exact positive provider timestamp included in the signature and deduplication key. |
| `extra_data_sha256` | Hash of required signed `extraData`; arbitrary decoded content is never stored or trusted. |
| `provider_occurred_at` | UTC instant parsed from `responseTime`. |
| `safe_optional_details` | Versioned allow-list for non-sensitive optional MoMo fields such as `paymentOption`/`userFee`; unknown fields are discarded, not copied wholesale. |
| `payload_sha256` | Integrity hash for reconciliation; it cannot reconstruct the prohibited raw payload. |
| `signature_key_version` | Identifier of the checksum/access-key configuration used for validation, never the secret itself. |
| `signature_verified_at` | UTC instant complete MoMo HMAC-SHA256 validation succeeded; required because only verified IPNs are persisted. |
| `processing_status` | Receipt outcome: received, processed, verified-but-unmatched, or internal processing failed. |
| `failure_code` | Stable safe diagnostic; null when processing succeeds. |
| `received_at` | UTC instant MentalBridge received the event. |
| `processed_at` | UTC instant business effects committed; null while unprocessed. |
| `acknowledged_at` | Best-effort UTC evidence that HTTP 204 was emitted; latency metrics remain authoritative for the 15-second SLA. |

### `consultation.payos_payment_webhook`

Minimal durable evidence of SDK-verified PayOS webhook or authenticated status
reconciliation. Invalid signatures do not persist attacker-controlled fields.
The table retains identity, amount, result class and one-way hashes; raw bodies,
signatures, payer bank accounts/names, descriptions and credentials are discarded.
Exact redelivery deduplicates, while distinct signed reused-reference/late/
unmatched events remain separate `VERIFIED_UNMATCHED` receipts for admin review.
Unknown signed webhook-confirmation samples are acknowledged without activation.
Receipt references intentionally are not unique; the unique settled reference
on `payment_transaction` protects financial use. Settlement locks the payment and
uses the same atomic credit/upgrade path for both receipt sources.

| Field | Purpose |
| --- | --- |
| `id` | Internal immutable receipt UUID shown only in bounded admin reconciliation. |
| `payment_id` | Nullable matched Consultation payment foreign key; unknown sample/order evidence has no owning payment. |
| `contract_version` | Required receipt normalization/SDK verification-policy version retained for historical interpretation. |
| `source` | Required `WEBHOOK` or `RECONCILIATION` provenance; distinguishes verified push from authenticated SDK query evidence without changing settlement rules. |
| `deduplication_key` | Required unique SHA-256 of the source and normalized verified event identity, so exact delivery/query replay has no second business effect. |
| `order_code` | Verified numeric external order identity, matched exactly with the PAYOS payment's reserved order code; unknown identities never activate rights. |
| `amount_minor` | Required non-negative verified integer VND amount; must equal frozen payment amount before settlement. Signed underpayment remains evidence, not success. |
| `payment_link_id` | Required verified opaque PayOS link identity; compared exactly to the locally confirmed link. |
| `reference` | Required verified opaque bank transaction reference; reused references may be retained on multiple unmatched receipts and cannot be settled on another payment. |
| `code` | Nullable bounded provider result code for safe classification; not a payment authorization by itself. |
| `success` | Nullable provider envelope success flag; never sufficient to settle without SDK verification and all identity/amount matches. |
| `payload_sha256` | Required one-way integrity hash of verified provider evidence; cannot reconstruct discarded sensitive payloads. |
| `signature_key_version` | Required non-secret configuration/verification policy label used to verify the receipt. |
| `signature_verified_at` | Required UTC instant SDK signature verification or authenticated query verification completed. |
| `processing_status` | Required `RECEIVED`, `PROCESSED`, `VERIFIED_UNMATCHED` or `PROCESSING_FAILED`; Consultation owns transitions and terminal review classifications. |
| `failure_code` | Nullable safe stable reason for mismatch/failure, with no raw provider text. |
| `received_at` | Required immutable UTC receipt ingestion instant. |
| `processed_at` | Nullable UTC instant processing and any financial effects committed. |
| `acknowledged_at` | Nullable best-effort UTC webhook HTTP acknowledgement evidence; reconciliation has no inbound webhook acknowledgement. |

### `consultation.subscription_upgrade`

Immutable calculation snapshot and workflow for the only supported v2
in-period change, `PLUS` to `PREMIUM`. Historical Premium Care/Premium Plus
rows retain their original plan-version provenance.

| Field | Purpose |
| --- | --- |
| `id` | Immutable upgrade UUID exposed in quote/status operations. |
| `subscription_id` | Current paid subscription being upgraded. |
| `from_plan_version_id` | `PLUS` version used for remaining-value calculation; historical v1 upgrade references remain valid. |
| `to_plan_version_id` | Higher `PREMIUM` version that starts a full new period after verified payment. |
| `payment_id` | Required unique upgrade payment attempt created with the quote in one transaction. |
| `old_period_start` | Snapshotted UTC start of the period being ended. |
| `old_period_end` | Snapshotted UTC end used to calculate actual total/remaining seconds. |
| `total_period_seconds` | Exact positive number of seconds in the old provider period; never assumed to be 30 days. |
| `remaining_period_seconds` | Exact positive seconds remaining at quote time, not greater than total seconds. |
| `remaining_feature_value_minor` | `floor(old non-consultation value × remaining / total)` in minor units. |
| `available_credit_value_minor` | Sum of allocations for old credits atomically moved from available to upgrade-held. |
| `offset_minor` | Applied non-withdrawable offset: sum of remaining feature and held-credit value for upgrade-offset-v1; exactly zero for sandbox-full-price-v1. Nominal credit value stays recorded separately. |
| `pricing_policy_version` | Snapshotted pricing rule selected from both immutable plan versions; historical rows default to upgrade-offset-v1. Mixed policy upgrades are rejected. |
| `amount_due_minor` | Exact target full-period price minus offset; positive and verified against payment. |
| `currency` | ISO 4217 currency shared by both plan versions, held credits, and payment. |
| `status` | Pending payment, applied, failed, or expired; only one pending upgrade per subscription. |
| `idempotency_key` | Subscription-scoped retry key returning the original quote/checkout. |
| `quoted_at` | UTC calculation instant used to determine remaining seconds. |
| `quote_expires_at` | UTC instant after which held credits must be released or expired. |
| `applied_at` | UTC instant verified payment atomically activated `PREMIUM`; null until applied. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest workflow-state change. |
| `version` | Optimistic-lock counter protecting webhook/expiry races. |

### `consultation.subscription_upgrade_credit`

Exact old credits held and valued by each upgrade quote. The `(upgrade_id,
credit_id)` primary key prevents a duplicate snapshot inside one quote. A credit
restored after a failed or expired upgrade may appear in a later quote while all
earlier allocation snapshots remain unchanged for audit. Checkout serialization,
the unique pending-upgrade index per subscription and the credit's
`UPGRADE_HELD` state prevent simultaneous pending upgrades from sharing it.

| Field | Purpose |
| --- | --- |
| `upgrade_id` | Upgrade whose quote includes the credit; part of the immutable snapshot's composite key. |
| `credit_id` | Local credit captured once per upgrade quote. Its identifier may recur across historical and later quotes after restoration; only an available credit can be newly held. |
| `allocated_value_minor` | Allocation snapshot included in the quote, retained for deterministic reconciliation. |

## Conceptual owner `consultation` (`mentalbridge_consultation.public`)

### `consultation.availability_slot`

Authoritative half-open 60-minute online slot published by an approved
specialist. MB-362 implements the v2 shape without PracticeLocation, phone, or
external meeting-link fields. Historical in-person appointment snapshots, if
introduced by a historical migration, remain separate and readable.

| Field | Purpose |
| --- | --- |
| `id` | Immutable slot UUID used in booking REST commands. |
| `specialist_account_id` | Consultation-owned specialist profile that owns the interval and serializes publication. |
| `start_at` | Inclusive UTC start instant of the available interval. |
| `end_at` | Exclusive UTC end instant, required to be later than start. |
| `timezone` | IANA timezone captured for stable human schedule rendering. |
| `modality` | Online mode; only `IN_APP_CHAT` or capability-gated `IN_APP_VIDEO` is accepted. |
| `status` | Authoritative `ACTIVE` or `WITHDRAWN` state used by the overlap exclusion constraint. |
| `idempotency_key` | Printable caller retry key unique per specialist; exact replay returns the same slot identity and current state, while conflicting reuse is rejected. |
| `withdrawn_at` | UTC instant at which the owner withdrew the future slot; null while active and retained for tombstone audit. |
| `created_at` | Immutable UTC slot creation instant. |
| `updated_at` | UTC instant of the latest slot state or schedule change. |
| `version` | Optimistic-lock counter preventing lost concurrent slot updates. |

### `consultation.specialist_earning`

Implemented by MB-516. One immutable VND allocation is created in the same
transaction as an evidence-backed `COMPLETED` appointment and exact credit
consumption. Elapsed time, `SESSION_ENDED`, no-show, cancellation, and released
or forfeited credits do not create an earning.

MB-812 adds prospective funded staging provenance through migration
`20261104100000-staging-demo-payout.sql`. Existing rows keep baseline policy,
null funding payment and unchanged amounts. The paired check allows only
baseline/null or staging-demo/non-null. The payment FK proves local identity;
the service additionally verifies exact processed successful PayOS receipt and
immutable paid-credit lineage at earning creation and payout preparation/retry.
No raw receipt or bank information is copied into earnings.

| Field | Purpose |
| --- | --- |
| `id` | Immutable earning UUID shown in specialist/admin history. |
| `appointment_id` | Unique completed appointment proving that one earning may exist. |
| `completion_fact_id` | Unique opaque completion evidence fact, preventing any second earning for the same completion. |
| `consumed_credit_id` | Unique consumed credit whose allocation funds the earning. |
| `specialist_account_id` | Specialist who completed the appointment and owns the payable amount. |
| `plan_version` | Entitlement/plan version snapshotted from the consumed credit period. |
| `earning_policy_version` | Required immutable calculation/provenance policy: default `specialist-earning-v1` preserves historical semantics; `staging-demo-paid-v1` records the explicitly gated real-funded demo calculation. Paired with funding_payment_id by CHECK. |
| `funding_payment_id` | Nullable same-owner FK to payment_transaction, required only for staging-demo-paid-v1. Binds the exact succeeded real PAYOS funding payment for reproducible amount and receipt eligibility; null for baseline/legacy rows. Never populated by backfill. |
| `currency` | ISO 4217 currency of all amounts in the earning. |
| `credit_allocation_minor` | Immutable whole-VND earning allocation: baseline 300,000; funded staging demo floor(actual collected amount / frozen purchased-plan credit count). Nominal period allocation is not rewritten. |
| `specialist_share_bps` | Revenue-share snapshot, currently 7000 (70%). |
| `specialist_amount_minor` | Whole-VND floor(credit_allocation_minor * specialist_share_bps / 10000), baseline 210,000; funded demo PLUS 875 or PREMIUM 700. Both demo division stages round down; total specialist earnings cannot exceed 70% of real collected money. |
| `platform_allocation_minor` | Earning allocation less the rounded specialist share; baseline 90,000 VND. This is not represented or described as platform profit. |
| `idempotency_source` | Stable completion-fact source preventing duplicate creation on settlement replay. |
| `status` | Settlement/payout state. `PAID` requires a linked payout with verified `SUCCEEDED` provider outcome. |
| `earned_at` | UTC appointment-completion instant. |
| `settlement_available_at` | Immutable UTC earned_at plus configured hold. Baseline minimum P7D; effective staging demo may use PT10M. Elapsed hold alone does not bypass dispute, provenance or destination eligibility. |
| `paid_at` | UTC verified payout-success instant; null until `PAID`. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest settlement/payout/reversal state change. |
| `version` | Optimistic-lock counter protecting settlement and payout races. |

### `consultation.specialist_payout_destination`

Immutable encrypted specialist-owned destination revision. Saving changed
provider, bank BIN, account or holder data creates a new UUID and disables the
previous current revision; reverting to older details also requires a new
confirmation. Identical saves of the current revision are no-ops. Raw account
and holder data appear only in the audited ADMIN detail response with no-store,
never in events, logs, list responses or the specialist read model.

| Field | Purpose |
| --- | --- |
| `id` | Immutable destination UUID referenced by payout requests. |
| `specialist_account_id` | Specialist who owns and may manage this destination. |
| `payout_provider` | Immutable `FAKE`, `MOMO` or `PAYOS` namespace. PAYOS requires a confirmed bank and separate staging readiness; historical MoMo destinations remain provider-affine. |
| `destination_type` | Allow-listed provider route: MoMo wallet or domestic bank account. |
| `destination_ciphertext` | AES-GCM encrypted JSON containing accountReference, accountHolderName, bankCode (six-digit BIN for PAYOS), and nullable bankName snapshot from the proxied bank list; none are plaintext columns. |
| `encryption_key_version` | Key identifier needed for controlled rotation and decryption. |
| `destination_fingerprint` | One-way fingerprint over provider/type/normalized BIN/account/holder for current-revision no-op detection. It is intentionally not owner-unique across history because reverting an account must create a pending revision. |
| `display_hint` | Safe masked label, such as a last-four hint, for specialist confirmation. |
| `status` | `PENDING_CONFIRMATION`, `VERIFIED` or `DISABLED`. PAYOS saves start pending; ADMIN confirms only the expected current pending revision. |
| `verified_at` | UTC manual confirmation instant for PAYOS, or retained legacy verification instant; null for pending revisions and required for VERIFIED. |
| `is_current` | Authoritative current revision flag; partial uniqueness permits one current row per specialist. Historical revisions remain immutable payout snapshots. |
| `confirmed_by` | External Identity ADMIN account UUID who manually checked the holder; nullable for pending and legacy FAKE/MOMO rows, required for VERIFIED PAYOS rows. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest verification or disablement change. |
| `version` | Optimistic-lock counter protecting concurrent destination changes. |

### `consultation.specialist_payout`

Idempotent provider payout request and its reconciled current outcome. Local/CI
uses FAKE. PAYOS is staging-only under ADR 0047's payout amendment with separate
credentials/readiness. Production and legacy/FAKE synthetic redemption remain
blocked; MB-812 permits only exact verified-funded staging demo snapshots.
Runtime FX is not allowed. Exact specialist command replay observes
the existing logical payout, including FAILED, without creating an attempt.

| Field | Purpose |
| --- | --- |
| `id` | Immutable payout request UUID. |
| `specialist_account_id` | Specialist receiving the attached available earnings. |
| `destination_id` | Immutable confirmed encrypted destination revision fixed for every attempt; an edit cannot retarget an existing payout. |
| `currency` | ISO 4217 currency shared by every attached earning. |
| `amount_minor` | Exact positive whole-VND requested amount, equal to attached payout items. MB-812 relaxes the DB floor additively from 100,000 to >0; service enforces configured minimum, at least 100,000 outside the effective staging demo gate (demo fallback 2,000). |
| `payout_provider` | Immutable `FAKE`/`MOMO`/`PAYOS` namespace used for every attempt and reconciliation, independent of the mode selected for new payouts. |
| `status` | `PENDING`, `PROCESSING`, `SUCCEEDED`, `FAILED`, or uncertainty-preserving `UNKNOWN`. |
| `idempotency_key` | Specialist-scoped command key preventing duplicate logical payout creation. Provider attempts derive separate keys. |
| `requested_on` | Server-local request date enforcing the planned maximum of one payout request per specialist per day. |
| `requested_at` | UTC instant MentalBridge created the payout request. |
| `completed_at` | UTC instant a verified provider attempt proved the logical payout succeeded; required for `SUCCEEDED`. |
| `last_failure_code` | Safe diagnostic from the latest definite failure; never contains destination/raw payload data. |
| `closed_at` | Nullable UTC ADMIN closure instant, allowed only for definitely FAILED payouts. Non-null forbids another attempt and releases active item ownership without changing original destination/amount/history. |
| `closed_by` | Nullable external Identity ADMIN UUID from the closing JWT; paired with closed_at for attribution, never a cross-service foreign key. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest verified reconciliation change. |
| `version` | Optimistic-lock counter protecting duplicate callbacks/status updates. |

### `consultation.specialist_payout_attempt`

Immutable provider-call lineage for one logical payout. Only an explicit
idempotent ADMIN command against the latest definitely FAILED attempt can create
a new numbered attempt. UNKNOWN/review attempts retain reservations and block
another transfer; mode changes cannot switch the stored provider. PAYOS uses a
one-item validated batch; the attempt UUID is the immutable child referenceId.
Only authenticated matching child success settles funds; outer approval alone
does not.

The provider-affinity foreign key is installed NOT VALID for existing rows
because older retry code could select a changed mode. Historical mixed-provider
lineage is retained verbatim; new attempts must match the parent's provider.
No migration reassigns historical money or provider references.

| Field | Purpose |
| --- | --- |
| `id` | Immutable provider-attempt UUID. |
| `payout_id` | Logical payout whose fixed earnings/destination/amount are being sent. |
| `payout_provider` | `MOMO`/`FAKE`/`PAYOS` namespace constrained by a composite foreign key to match the parent payout. |
| `attempt_number` | Monotonic sequence within the logical payout. |
| `provider_idempotency_key` | Immutable unique provider-scoped transfer key; PAYOS requires UUIDv4 and uses it as explicit x-idempotency-key plus outer batch referenceId. Uncertain delivery is queried, never resubmitted. Legacy MoMo colon keys remain valid. |
| `provider_payout_reference` | Unique provider outer payout identity when known. PAYOS list recovery resolves this separately from a child transaction ID. |
| `provider_transfer_reference` | Nullable unique provider child transaction identity established by matched authenticated evidence; distinct from the local attempt UUID used as child referenceId. |
| `status` | Provider-call outcome including `UNKNOWN` for timeout/ambiguous delivery. |
| `reconciliation_status` | `PENDING`, `POLLING`, `NEEDS_REVIEW`, or locally terminal `COMPLETE` including definite failure. |
| `next_reconciliation_at` | Nullable persisted UTC next polling instant; cleared for terminal/review attempts. |
| `reconciliation_started_at` | Nullable immutable UTC first submit-start instant; independent of subsequent polls or provider outages. |
| `reconciliation_deadline_at` | Nullable absolute UTC horizon constrained to exactly 24 hours after first start; reaching it preserves UNKNOWN funds and stops automatic polling. |
| `reconciliation_lease_until` | Nullable UTC worker-claim expiry preventing concurrent automatic polls while allowing crash recovery. |
| `reconciliation_lease_token` | Nullable opaque UUID fencing a stale worker from applying a result after its claim was superseded. |
| `review_required_at` | Nullable UTC first operator-review instant; overdue outcomes remain reserved and do not imply failure. |
| `reconciliation_count` | Nonnegative persisted poll count selecting bounded 5s/15s/30s/60s/2m/5m backoff. |
| `requested_at` | UTC instant the attempt was sent or queued for sending. |
| `provider_confirmed_at` | UTC instant verified provider evidence proved success. |
| `failed_at` | UTC instant a definite terminal failure was verified. |
| `failure_code` | Safe stable failure code without sensitive payload data. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest provider reconciliation update. |
| `version` | Optimistic-lock counter protecting callback/status-query races. |

### `consultation.payout_destination_confirmation_command`

Durable ADMIN-scoped idempotency evidence for a successful manual holder check.
The command and audit outbox record commit with confirmation. Replaying after a
later edit observes the original revision without confirming the replacement.

| Field | Purpose |
| --- | --- |
| `id` | Immutable internal confirmation-command UUID. |
| `actor_account_id` | External Identity ADMIN UUID from JWT subject, scopes the key and audit attribution. |
| `idempotency_key` | ADMIN-scoped 16–128-character key; identical destination/version/reason is a no-op, conflicting reuse returns 409. |
| `destination_id` | Local foreign key to the exact immutable checked bank revision. |
| `expected_version` | Nonnegative caller-observed revision version required under lock before confirmation. |
| `reason_code` | Fixed ACCOUNT_HOLDER_CHECKED attestation without raw account/name or unrestricted notes. |
| `result_version` | Persisted confirmation version constrained to expected_version plus one. |
| `created_at` | Immutable UTC committed confirmation instant. |

### `consultation.payout_retry_command`

Durable ADMIN-scoped command deduplication for explicit FAILED-only retry.
Earning items, amount, currency, destination and provider remain fixed; both
attempt foreign keys must belong to the same logical payout.

| Field | Purpose |
| --- | --- |
| `id` | Immutable internal retry-command UUID. |
| `actor_account_id` | External Identity ADMIN UUID from JWT subject, scopes the key and audit attribution. |
| `idempotency_key` | ADMIN-scoped 16–128-character key; transport replay observes result_attempt_id, while conflicting body reuse returns 409. |
| `payout_id` | Immutable logical payout retaining its original earning allocations. |
| `expected_attempt_id` | Latest definitely FAILED attempt checked under locks; cannot equal the result attempt. |
| `result_attempt_id` | Newly persisted numbered attempt whose dispatch may occur only after command commit. |
| `created_at` | Immutable UTC command insertion instant. |

### `consultation.payout_close_command`

Durable ADMIN-scoped idempotency evidence for closing a definitely FAILED
payout without calling its provider. Closing and item release commit with the
audit outbox record. Same key/body is a no-op; conflicting reuse is rejected.

| Field | Purpose |
| --- | --- |
| `id` | Immutable internal close-command UUID. |
| `actor_account_id` | External Identity ADMIN UUID from JWT, scopes the key and audit attribution. |
| `idempotency_key` | ADMIN-scoped 16–128-character key retained for replay deduplication. |
| `payout_id` | Failed logical payout whose immutable history is retained. |
| `expected_attempt_id` | Latest FAILED/COMPLETE attempt required under lock; composite foreign key requires the same payout. |
| `reason_code` | Fixed DESTINATION_UNUSABLE explanation, without bank data or free text. |
| `created_at` | Immutable UTC command and closure instant. |

### `consultation.specialist_payout_item`

Immutable allocation history of earnings to payout requests. A partial unique
index on earning_id where released_at is null permits at most one active payout
owner. An ADMIN can close only a latest definitely FAILED/COMPLETE payout,
atomically release its items and retain all historical amounts and links.
New withdrawal eligibility still applies; closure does not bypass disputes,
hold, fixture provenance, minimum, confirmation or the daily logical cap.

| Field | Purpose |
| --- | --- |
| `payout_id` | Provider payout request containing the earning. |
| `earning_id` | Earning attached to at most one active payout; closed failure links remain historical. |
| `amount_minor` | Exact portion requested from this earning; it becomes paid only when the parent payout succeeds. Current flow uses the full available specialist amount. |
| `released_at` | Nullable UTC ADMIN closure instant releasing active ownership, never deleting or changing the historical allocation. Only definite failure closure writes it. |

### `consultation.specialist_payout_status_history`

Append-only lifecycle evidence for a payout request, including uncertainty and reconciliation.

| Field | Purpose |
| --- | --- |
| `id` | Immutable transition UUID. |
| `payout_id` | Payout whose current status changed. |
| `from_status` | Prior status; null for initial creation. |
| `to_status` | New validated status based on local command or verified provider evidence. |
| `reason_code` | Safe machine-readable reason without raw provider/destination data. |
| `changed_at` | UTC instant the transition committed. |

### `consultation.payout_provider_event`

Deduplicated receipt of payout IPN/callback events. Only the integrity hash and safe processing metadata are retained.

| Field | Purpose |
| --- | --- |
| `id` | Internal immutable event-receipt UUID. |
| `payout_attempt_id` | Optional resolved local attempt; null until the provider identity can be correlated safely. |
| `payout_provider` | `MOMO`/`FAKE`/`PAYOS` namespace for evidence identity and verification policy. PAYOS introduces no payout webhook. |
| `provider_event_id` | Provider event identity, unique within the provider for replay protection. |
| `event_type` | Allow-listed event category used for reconciliation routing. |
| `payload_sha256` | Integrity hash; raw callback payload and destination data are not stored here. |
| `processing_status` | Receipt state: received, processed, safely rejected, or failed. |
| `failure_code` | Stable safe processing diagnostic; null on success. |
| `received_at` | UTC instant MentalBridge received the callback. |
| `processed_at` | UTC instant its verified effect committed; null while unresolved. |

### `consultation.appointment_rating`

One current bounded rating tied to an evidence-backed completed appointment.
MB-364 does not collect a comment, anonymous flag, diagnosis, or moderation
payload.

| Field | Purpose |
| --- | --- |
| `appointment_id` | Primary key and local foreign key proving exactly one current rating opportunity for the completed appointment. |
| `user_account_id` | External Identity owner UUID copied from the locked appointment; callers cannot supply it. |
| `specialist_account_id` | Local specialist foreign key copied from the locked appointment for aggregate maintenance. |
| `rating` | Validated integer rating from one through five. |
| `created_at` | Immutable UTC review submission instant. |
| `updated_at` | UTC instant of the latest owner edit. |
| `version` | Optimistic-lock counter preventing lost rating edits. |

### `consultation.specialist_rating_aggregate`

Transactionally maintained display/ranking projection. It contains no review
text and is never accepted from a client.

| Field | Purpose |
| --- | --- |
| `specialist_account_id` | Primary/local foreign key for the rated specialist. |
| `rating_count` | Number of distinct current appointment ratings; always positive while the row exists. |
| `rating_sum` | Exact sum used with count to derive a two-decimal average without accumulating rounding error. |
| `version` | Monotonic aggregate update counter for operational verification. |
| `updated_at` | UTC instant of the latest create/edit contribution. |
