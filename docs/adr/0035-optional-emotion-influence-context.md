# ADR 0035: Optional private context for daily emotion check-ins

- Status: Accepted for MB-658 implementation
- Date: 2026-10-12
- Decision ID: `MB-EMOTION-CONTEXT-001`
- Extends: [ADR 0018](0018-daily-emotion-check-in.md), Decision ID `MB-DAILY-EMOTION-CHECK-IN-001`
- Owner: Journal/AI
- Policy: [Daily emotion check-in policy v2](../policies/daily-emotion-check-in-policy-v2.md)

## Context

The daily check-in already supports an optional encrypted private note, while
influence choices previously had no durable contract. A person must be able to
skip all context, reload what they explicitly saved, clear it, and delete it
without the product treating context as a questionnaire or clinical signal.

## Decision

The owner value adds an optional `factors` array using exactly `sleep`, `work`,
`study`, `family`, `friends`, `health`, and `other`. At most three unique values
are accepted and stored in canonical contract order. Omission is equivalent to
an empty array. The private note remains optional, non-blank when present, and
bounded to 500 characters.

Factors and note are encrypted inside the existing per-revision AES-256-GCM
payload. The user's explicit selection and save is the consent action; the UI
states what is retained before the optional controls are used. No separate Care
consent record is introduced because the values remain in the owner-scoped
record and are not disclosed to another purpose or domain.

Existing records decrypt with `factors: []`. Existing clients may omit factors.
The MongoDB collection validator continues to validate only the encrypted
envelope, so no document rewrite or validator migration is required. The API
always returns a normalized factor array to updated owner clients.

Factors and raw notes are excluded from AI reflection context, notification
activity, SupportPlan, reassessment, analytics, events, metrics, and logs.
Adding any downstream use requires a separate approved minimal-data policy and
contract. Direct deletion erases them with all encrypted revisions; active
record retention and the content-free tombstone remain unchanged.

## Consequences

- Emotion and intensity remain the only required check-in fields.
- Same-day optimistic revision, idempotency, local-day, owner isolation, and
  deletion behavior apply to factors and note without a second store.
- Web and mobile show one collapsed, skippable context section with localized
  labels and clear privacy copy.
- The service rejects unsupported, duplicate, and oversized factor arrays and
  oversized or blank notes before encryption.

## Rejected alternatives

- Storing factors in plaintext for filtering was rejected because the values
  are private self-reported context and have no approved analytics use.
- Reusing AI-processing consent was rejected because this owner-only save does
  not authorize AI processing or cross-domain disclosure.
- Persisting free-form factor labels was rejected because it would complicate
  validation, localization, minimization, and compatibility.
