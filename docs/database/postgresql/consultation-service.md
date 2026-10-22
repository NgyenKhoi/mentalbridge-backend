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
| `pricing_policy_version` | Immutable rule: ordinary/historical `upgrade-offset-v1` or synthetic `sandbox-full-price-v1`; sandbox upgrade applies zero offset and cannot enable real payout. |
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
| `ended_at` | UTC instant the paid subscription ceased being current; null while active/pending. |
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

MoMo-backed payment attempt and current externally confirmed outcome. `FAKE` may exercise the same MoMo-shaped contract in local/CI; it is not a second production payment method. The MVP creates no refund transaction.

| Field | Purpose |
| --- | --- |
| `id` | Immutable payment UUID exposed in bounded payment history. |
| `subscription_id` | Paid subscription receiving an initial purchase or upgrade. |
| `target_plan_version_id` | Exact immutable plan version whose price and rights were used to create this attempt. |
| `payment_provider` | `MOMO` in real environments or MoMo-shaped `FAKE` in local/CI; no other production provider is supported. |
| `momo_order_id` | Merchant-generated MoMo `orderId`, unique per provider and used as the primary IPN correlation key. |
| `momo_request_id` | Merchant-generated MoMo `requestId`, unique per provider and matched exactly on IPN. |
| `momo_trans_id` | Positive MoMo transaction ID when issued. Zero/unusable values are not copied from a failed IPN; positive values are unique. |
| `amount_minor` | Exact positive amount requested/confirmed in currency minor units. |
| `currency` | ISO 4217 currency validated against the plan/upgrade quote. |
| `status` | `PENDING`, `SUCCEEDED`, `FAILED`, or externally reported `CHARGEBACK`; refund states are unsupported. |
| `purpose` | Distinguishes historical purposes. New v2 user-facing operations create only purchase or upgrade attempts; no downgrade/refund purpose exists. |
| `momo_result_code` | Latest verified MoMo `resultCode`; only final code `0` can produce `SUCCEEDED`. |
| `momo_pay_type` | Verified MoMo `payType`, such as QR or app payment. |
| `idempotency_key` | Subscription-scoped payment retry key preventing duplicate attempts. |
| `momo_response_time_epoch_ms` | Exact verified MoMo millisecond timestamp retained for signature/reconciliation evidence. |
| `provider_occurred_at` | UTC instant parsed from verified MoMo `responseTime`; null while checkout is pending. |
| `paid_at` | Provider-confirmed UTC success instant; null until successful. |
| `failed_at` | UTC terminal failure instant; null unless failed. |
| `expires_at` | UTC deadline after which the pending attempt can no longer activate rights. |
| `checkout_url` | Nullable safe provider redirect. Null while durable checkout preparation is awaiting an unconfirmed provider response (including timeout); no redirect or automatic charge resend. Verified callback may finalize the payment even when null. Historical URLs are unchanged. Never authorization evidence. |
| `qr_code_url` | Optional safe MoMo QR URL returned with checkout. |
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

Exact old credits held and valued by one upgrade quote.

| Field | Purpose |
| --- | --- |
| `upgrade_id` | Upgrade whose offset includes the credit. |
| `credit_id` | Unique credit held by at most one upgrade; reserved/consumed credits cannot appear. |
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

| Field | Purpose |
| --- | --- |
| `id` | Immutable earning UUID shown in specialist/admin history. |
| `appointment_id` | Unique completed appointment proving that one earning may exist. |
| `completion_fact_id` | Unique opaque completion evidence fact, preventing any second earning for the same completion. |
| `consumed_credit_id` | Unique consumed credit whose allocation funds the earning. |
| `specialist_account_id` | Specialist who completed the appointment and owns the payable amount. |
| `plan_version` | Entitlement/plan version snapshotted from the consumed credit period. |
| `currency` | ISO 4217 currency of all amounts in the earning. |
| `credit_allocation_minor` | Exact fixed allocation snapshot, currently 300,000 VND per consumed credit. |
| `specialist_share_bps` | Revenue-share snapshot, currently 7000 (70%). |
| `specialist_amount_minor` | Exact specialist amount, currently 210,000 VND. It is never derived from subscription price. |
| `platform_allocation_minor` | Remaining 90,000 VND allocation. This is not represented or described as platform profit. |
| `idempotency_source` | Stable completion-fact source preventing duplicate creation on settlement replay. |
| `status` | Settlement/payout state. `PAID` requires a linked payout with verified `SUCCEEDED` provider outcome. |
| `earned_at` | UTC appointment-completion instant. |
| `settlement_available_at` | UTC instant seven days after earning at which it becomes payout eligible. |
| `paid_at` | UTC verified payout-success instant; null until `PAID`. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest settlement/payout/reversal state change. |
| `version` | Optimistic-lock counter protecting settlement and payout races. |

### `consultation.specialist_payout_destination`

Encrypted specialist-owned destination used by a provider payout adapter. Raw wallet/account data never enters logs, events, or broad read models.

| Field | Purpose |
| --- | --- |
| `id` | Immutable destination UUID referenced by payout requests. |
| `specialist_account_id` | Specialist who owns and may manage this destination. |
| `payout_provider` | `MOMO` in real environments or MoMo-shaped `FAKE` locally; only MoMo may be used in production after credential approval. |
| `destination_type` | Allow-listed provider route: MoMo wallet or domestic bank account. |
| `destination_ciphertext` | Encrypted provider-required wallet/account details; never returned as stored ciphertext to clients. |
| `encryption_key_version` | Key identifier needed for controlled rotation and decryption. |
| `destination_fingerprint` | One-way normalized fingerprint used for duplicate detection without revealing destination data. |
| `display_hint` | Safe masked label, such as a last-four hint, for specialist confirmation. |
| `status` | Verification/availability state; only `VERIFIED` destinations accept new payouts. |
| `verified_at` | UTC provider/application verification instant; required for `VERIFIED`. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest verification or disablement change. |
| `version` | Optimistic-lock counter protecting concurrent destination changes. |

### `consultation.specialist_payout`

Idempotent provider payout request and its reconciled current outcome. Local/CI
uses a fake adapter; real domestic payout stays disabled until exact VND plan
prices, fixed `creditAllocation`, and MoMo credentials exist. Runtime FX is not
allowed.

| Field | Purpose |
| --- | --- |
| `id` | Immutable payout request UUID. |
| `specialist_account_id` | Specialist receiving the attached available earnings. |
| `destination_id` | Verified encrypted payout destination selected for this request. |
| `currency` | ISO 4217 currency shared by every attached earning. |
| `amount_minor` | Exact positive requested amount in minor units, equal to attached payout items. |
| `payout_provider` | `MOMO`/`FAKE` namespace used for request, status, and IPN reconciliation. |
| `status` | `PENDING`, `PROCESSING`, `SUCCEEDED`, `FAILED`, or uncertainty-preserving `UNKNOWN`. |
| `idempotency_key` | Specialist-scoped command key preventing duplicate logical payout creation. Provider attempts derive separate keys. |
| `requested_on` | Server-local request date enforcing the planned maximum of one payout request per specialist per day. |
| `requested_at` | UTC instant MentalBridge created the payout request. |
| `completed_at` | UTC instant a verified provider attempt proved the logical payout succeeded; required for `SUCCEEDED`. |
| `last_failure_code` | Safe diagnostic from the latest definite failure; never contains destination/raw payload data. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest verified reconciliation change. |
| `version` | Optimistic-lock counter protecting duplicate callbacks/status updates. |

### `consultation.specialist_payout_attempt`

Immutable provider-call lineage for one logical payout. A definite failed attempt may be followed by a new numbered attempt; an `UNKNOWN` attempt must be queried/reconciled and blocks a new transfer attempt.

| Field | Purpose |
| --- | --- |
| `id` | Immutable provider-attempt UUID. |
| `payout_id` | Logical payout whose fixed earnings/destination/amount are being sent. |
| `payout_provider` | `MOMO`/`FAKE` namespace, constrained to match the parent payout. |
| `attempt_number` | Monotonic sequence within the logical payout. |
| `provider_idempotency_key` | Unique provider-scoped transfer key; retries of the same network call reuse it. |
| `provider_payout_reference` | Provider transfer identity when known, unique within provider. |
| `status` | Provider-call outcome including `UNKNOWN` for timeout/ambiguous delivery. |
| `requested_at` | UTC instant the attempt was sent or queued for sending. |
| `provider_confirmed_at` | UTC instant verified provider evidence proved success. |
| `failed_at` | UTC instant a definite terminal failure was verified. |
| `failure_code` | Safe stable failure code without sensitive payload data. |
| `created_at` | Immutable UTC insertion instant. |
| `updated_at` | UTC instant of the latest provider reconciliation update. |
| `version` | Optimistic-lock counter protecting callback/status-query races. |

### `consultation.specialist_payout_item`

Immutable allocation of available earnings to one payout request.

| Field | Purpose |
| --- | --- |
| `payout_id` | Provider payout request containing the earning. |
| `earning_id` | Unique earning attached to at most one payout. |
| `amount_minor` | Exact portion requested from this earning; it becomes paid only when the parent payout succeeds. Current flow uses the full available specialist amount. |

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
| `payout_provider` | `MOMO`/`FAKE` namespace for event identity and verification policy. |
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
