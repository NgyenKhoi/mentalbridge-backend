# Use-case and WBS coverage

This is the planning coverage baseline for the workbook's seven use cases and 162 functions. `Planned` means assigned to an owner and specified, not implemented. `Blocked` means the workbook requires behavior whose authoritative boundary or policy is not yet accepted.

## Catalogue coverage

| Catalogue UC | Primary owner | Collaborators | Module capability | Status |
| --- | --- | --- | --- | --- |
| UC-01 Authentication & User Management | Identity/Care | Content/Notification, all deletion owners | Auth, RBAC, profile/consent, grants, deletion, anonymous entry | Planned; retention/export/expiry policies blocked |
| UC-02 Mental Health Assessment & AI Analysis | Care/Journal-AI | PhoBERT, Content/Notification | Journal, assessment, analysis, risk and personal analytics | Planned; risk and dataset policies blocked |
| UC-03 Intervention & Support | Care/Content-Notification | Journal/AI | Intervention, crisis/self-help content and notifications | Planned; reviewed crisis policy blocked |
| UC-04 Specialist Discovery & Appointment | Consultation plus unresolved financial owner | Care, Realtime, Content/Notification | Discovery, subscription/payment/credits, specialist, booking, reviews | Partially planned; financial boundary blocked |
| UC-05 Communication & Follow-up | Realtime/Care | Consultation, Content/Notification | Chat, reports, follow-up, notifications and progress | Planned; moderation/retention policy blocked |
| UC-06 Specialist Portal | Consultation plus unresolved financial owner | Care, Journal/AI, Realtime | Workload, consented data, earnings and payouts | Partially planned; financial boundary blocked |
| UC-07 Administration | Owner-specific APIs/projections | All | Accounts, finance, content, moderation, appointments, evaluation, reporting, audit | Partially planned; finance/retention/cohort policies blocked |

## WBS ownership coverage

| WBS range | Owner specification | Coverage note |
| --- | --- | --- |
| 1-5, 7, 111, 113-116 | Identity | Authentication, RBAC, admin login and user administration |
| 6, 8-14, 20-24, 30-31, 89-94, 98-101 | Care | Screening, profile/consent, assessment, risk, follow-up, analytics |
| 15-19, 25-29, 150-156 | Journal/AI and PhoBERT | Journals, analysis, datasets and benchmarks |
| 32-35, 95-97, 130-139 | Content/Notification | Crisis/self-help resources and notifications |
| 36-41, 52-73, 83-88, 102-107, 117-122, 140-144, 148-149 | Consultation | Discovery, specialists, booking, reviews and bounded portal/admin views |
| 42-51, 108-110, 123-129 | Unresolved financial owner | Subscription, payment, credits, earnings and payouts; blocked pending ADR |
| 74-82, 145-147 | Realtime | Conversations, messages, receipts and chat moderation |
| 112, 157-158 | Owner projections | Admin dashboard and trends without runtime distributed joins |
| 159-162 | Identity coordinator plus every owner | Audit and retention |

All 162 WBS functions are mapped, but financial ranges are deliberately unassigned to a deployable until an ADR establishes authoritative ownership. Mapping is not proof that every workbook row has a testable scenario; each exact title still needs an acceptance/test reference in its owner backlog.

## Cross-module end-to-end acceptance

- Guest screening returns deterministic result and relevant crisis guidance synchronously; registration never silently claims anonymous data.
- Authenticated assessment commits score, risk result, and outbox atomically; duplicate submission returns the original outcome.
- Journal remains usable when AI fails; analysis requires current AI consent and results cannot override Care safety rules.
- Revoked specialist access blocks new sensitive reads under concurrent access and produces a minimized audit fact.
- Two simultaneous bookings for one slot yield exactly one active appointment; eligible confirmed participants can chat and recover history after reconnect.
- Notification-provider failure never changes the underlying domain outcome and is visible as a retryable/terminal delivery attempt.
- Account deletion is idempotent across all data owners and does not restore data after retries or backup recovery.

## Product decisions that block implementation

1. Exact risk matrix, freshness windows, thresholds, and PHQ-9 item 9 response.
2. Specialist qualification evidence and approval rules.
3. Reviewed crisis resources, locale ownership, cadence, and after-hours wording.
4. Specialist-note ownership and retention, if notes remain in scope.
5. Minimum age/guardian behavior beyond the current 18-30 scope.
6. Consent wording/versioning, retention, deletion SLA, export scope, and legal review.
7. Consultation channel, cancellation/reschedule deadlines, and external contact/link rules.
8. Dataset licenses, label mapping, leakage controls, ethics approval, and metadata edit semantics.
9. Moderation evidence scope, access, actions, appeals, and retention.
10. Reporting cohort minimums and projection freshness expectations.
11. Financial service ownership, payment provider/method, signed webhook contract, subscription lifecycle, immutable ledger, credit reservation/consume/return/expiry, refunds/chargebacks, specialist earnings, payout settlement, reconciliation and financial retention.
12. Whether benchmark WBS 28-29 and admin WBS 155-156 are separate actor-specific use cases or duplicate entries.
