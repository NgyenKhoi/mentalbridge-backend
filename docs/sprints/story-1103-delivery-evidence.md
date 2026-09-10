# Story 1103 — combined screening interpretation and support routing

## Delivered scope

- Authenticated `POST /api/v1/support-evaluations` accepts exactly one explicit owned PHQ-9 ID and one explicit owned GAD-7 ID; it never searches for a latest result.
- Care rejects missing/foreign, duplicate, voided, incomplete, wrong-instrument, or policy-incompatible evidence.
- `mb-support-routing-capstone-v1` applies safety-first deterministic routing and returns an immutable tier, stable ordered reasons, exact evidence references, policy version, evaluation time, separate per-instrument meanings, one bounded next step, and a non-diagnostic disclaimer. No composite score is created.
- A profile row lock, per-user idempotency constraint, evidence-pair uniqueness, and transactional outbox prevent conflicting concurrent decisions and duplicate events.
- Policy definitions, compatibility allow-list, nine Vietnamese 14-day band meanings, three bounded next steps, and the evaluation are stored by append-only Liquibase change `care-009-combined-support-routing`.
- The `care.support-tier.resolved` v1 event is minimized and excludes raw answers, scores, item-9 values, and free-form clinical reasoning.

## Safety/content decision

On 2026-09-10 the Product Owner explicitly approved this minimum controlled-Capstone fallback:

> Nếu bạn cảm thấy mình không an toàn hoặc có nguy cơ gây hại cho bản thân, hãy chủ động liên hệ dịch vụ khẩn cấp hoặc cơ sở y tế phù hợp tại khu vực của bạn.

The reviewed string is local immutable Care policy data and is also retained as the safety runtime fallback. Routing and minimum safety guidance have no Content, notification, AI, Kafka, Redis, Realtime, specialist, booking, or other synchronous network dependency.

## Verification

- Full Care suite: 82 tests passed, including real PostgreSQL/Testcontainers migration and integration coverage.
- Story-specific coverage includes safety priority, stable reason ordering, non-composite response shape, authentication/role enforcement, owner isolation, duplicate/voided/wrong-instrument evidence rejection, idempotent replay, transactional outbox creation, and concurrent same-pair serialization.
- OpenAPI, event schema, generated frontend Care types, frontend lint/typecheck/contracts/unit tests, and production build pass.

## Explicit non-goals

This Story does not publish personalized interventions, contact a specialist or third party, book an appointment, share assessment data, schedule reminders, infer suicide intent/plan/imminence/urgency, or make the controlled Capstone policy suitable for production clinical deployment. Those capabilities retain separate gates.
