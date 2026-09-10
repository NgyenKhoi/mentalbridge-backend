# Use-case and WBS coverage

This is the planning coverage baseline for the workbook's seven use cases and 162 functions. `Planned` means assigned to an owner and specified, not implemented. `Blocked` means the workbook requires behavior whose authoritative boundary or policy is not yet accepted.

## Catalogue coverage

| Catalogue UC | Primary owner | Collaborators | Module capability | Status |
| --- | --- | --- | --- | --- |
| UC-01 Authentication & User Management | Identity/Care | Content/Notification, all deletion owners | Auth, RBAC, profile/consent, grants, deletion, anonymous entry | Planned; retention/export/expiry policies blocked |
| UC-02 Mental Health Assessment & AI Analysis | Care/Journal-AI | PhoBERT, Content/Notification | Journal, assessment, analysis, safety/support and personal analytics | Vietnamese PHQ-9 v2 and GAD-7 controlled-Capstone runtimes published; PHQ-9 v1 retained for history; AI personalization deferred |
| UC-03 Intervention & Support | Care/Content-Notification | Journal/AI | Approved intervention, safety/self-help guidance and notifications | Product routing blueprint approved; personalized routing/actions runtime unavailable pending separate gates |
| UC-04 Specialist Discovery & Appointment | Consultation/Billing | Care, Realtime, Content/Notification | Discovery, subscription/payment/upgrade/credits, specialist approval, booking, earnings, reviews | Planned by ADR 0005; provider/configuration details remain |
| UC-05 Communication & Follow-up | Realtime/Care | Consultation, Content/Notification | Chat, reports, follow-up, notifications and progress | Planned; moderation/retention policy blocked |
| UC-06 Specialist Portal | Consultation/Billing | Care, Journal/AI, Realtime | Workload, consented data, earnings and provider payout history | Planned; MoMo adapter conditional on credentials/currency decision |
| UC-07 Administration | Owner-specific APIs/projections | All | Accounts, finance, content, moderation, appointments, evaluation, reporting, audit | Partially planned; provider/retention/cohort policies blocked |

## WBS ownership coverage

| WBS range | Owner specification | Coverage note |
| --- | --- | --- |
| 1-5, 7, 111, 113-116 | Identity | Authentication, RBAC, admin login and user administration |
| 6, 8-14, 20-24, 30-31, 89-94, 98-101 | Care | Screening, profile/consent, assessment, safety/support, follow-up, analytics |
| 15-19, 25-29, 150-156 | Journal/AI and PhoBERT | Journals, analysis, datasets and benchmarks |
| 32-35, 95-97, 130-139 | Content/Notification | Reviewed self-help resources and notifications; hotline catalogue removed |
| 36-41, 52-53, 55-73, 83-88, 102-107, 117-122, 140-144, 148-149 | Consultation | Discovery, specialist approval without WBS 54 document upload, booking, reviews and bounded portal/admin views |
| 42-51, 108-110, 123-129 | Consultation/Billing | Subscription, payment, Care-to-Plus upgrade, credits, earnings and provider payout history under ADR 0005 |
| 74-82, 145-147 | Realtime | Conversations, messages, receipts and chat moderation |
| 112, 157-158 | Owner projections | Admin dashboard and trends without runtime distributed joins |
| 159-162 | Identity coordinator plus every owner | Audit and retention |

The workbook's 162 rows remain traceable; WBS 54 is explicitly removed by the 2026-08-21 product decision and the other 161 functions are mapped to owners. Mapping is not proof that every active row has a testable scenario; each exact title still needs an acceptance/test reference in its owner backlog.

## Cross-module end-to-end acceptance

- Guest screening returns deterministic result and reviewed safety guidance synchronously; registration never silently claims anonymous data.
- Authenticated assessment commits score, independent safety status, and outbox atomically; duplicate submission returns the original outcome.
- Journal remains usable when AI fails; analysis requires current AI consent and results cannot override Care safety rules.
- Revoked specialist access blocks new sensitive reads under concurrent access and produces a minimized audit fact.
- Concurrent booking/upgrade can use a credit only once; two bookings for one slot yield exactly one active appointment; only eligible confirmed appointment participants can join/send during the snapshotted slot and recover permitted read-only history after reconnect.
- Notification-provider failure never changes the underlying domain outcome and is visible as a retryable/terminal delivery attempt.
- Account deletion is idempotent across all data owners and does not restore data after retries or backup recovery.

## Product decisions that block implementation

1. `mb-support-routing-capstone-v1` is approved as a non-executable current-result blueprint. Runtime contracts/persistence/tests, any automatic freshness window and the personalized intervention catalogue remain open; PHQ-9 Vietnamese Capstone content and item-9 behavior are published.
2. Specialist qualification evidence and approval rules.
3. Reviewed Vietnamese safety/disclaimer wording and decision on any specific emergency number; no hotline catalogue or after-hours safety service is planned.
4. Specialist-note ownership and retention, if notes remain in scope.
5. Minimum age/guardian behavior beyond the current 18-30 scope.
6. Consent wording/versioning, retention, deletion SLA, export scope, and legal review.
7. Standard appointment duration, join grace, read-only chat history, cancellation/reschedule deadline, and later `IN_APP_VIDEO` signalling/provider/security contract.
8. Dataset licenses, label mapping, leakage controls, ethics approval, and metadata edit semantics.
9. Moderation evidence scope, access, actions, appeals, and retention.
10. Reporting cohort minimums and projection freshness expectations.
11. Exact MoMo request type/payment methods, credential/key rotation, settlement delay, chargeback reconciliation, payout onboarding, VND plan prices or versioned FX policy, and financial retention. ADR 0005 fixes the MoMo-only IPN contract, ownership, plan values, upgrade, credit, earning, and payout states; downgrade/refund remain unsupported.
12. Whether benchmark WBS 28-29 and admin WBS 155-156 are separate actor-specific use cases or duplicate entries.
