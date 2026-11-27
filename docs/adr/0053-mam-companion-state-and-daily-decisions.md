# Mầm companion state and daily decisions

Status: Accepted for the owner's Mầm companion v3 request of 2026-11-27.

Decision ID: `MB-MAM-COMPANION-STATE-001`

## Context

Review feedback said Mầm was technically complete but gave no clear reason to
come back: no objective, no thread from one day to the next, and no evidence of
the user's own change. Guide and one-time moment state lived in browser
storage, so it was lost on a device change.

## Decision

### Objective of Mầm

Mầm leads the user through one 14-day chặng around a focus the user picks
after the screening result, asks back the small commitments the user made, and
at day 14 asks "Điều đó nhẹ đi chưa?". Its objective, in order: the user
understands their own feelings, does small things regularly, reaches real
people (a specialist, someone they trust) when needed, and needs Mầm less over
time. Mầm never optimises app opens or message counts. Mầm stays a companion: it never
diagnoses, scores recovery, shames inactivity or mutates a SupportPlan
(support-plan-companion-policy-v1). AI understands, Care decides, the user
confirms. Safety never waits on Mầm: Mầm is hidden on safety surfaces and when
the combined result is SAFETY.

### Daily decision table

Mầm is hidden on safety surfaces and on a SAFETY result. The first-run guide
and one-time moments keep their existing priority. For the first proactive line
of a local day, the web client then evaluates a deterministic table, first match
wins. Every proactive line is fixed copy; a model call happens only after the
user clicks.

| Order | Condition (self-report and Care states only) | Mầm does |
| --- | --- | --- |
| 1 | Trajectory PERSISTENT_LOW, FIRST_LOW or DIP_AFTER_GOOD | Comforts first; no task |
| 2 | An OPEN commitment due yesterday or today | Asks it back (done, not yet, or swap on the Home card) |
| 3 | The plan review is due | Points to the 14-day look-back |
| 4 | Screening done, no focus, not asked in the last 3 days | Invites the 14-day focus pick |
| 5 | BOUNCE_BACK, IMPROVING, STEADY_GOOD, WELCOME_BACK or FLAT | The existing trajectory reaction (cheer only when the band allows) |
| 6 | Anything else | Silence |

Needing Mầm less: while the trajectory is STEADY_GOOD, Mầm speaks at most one
proactive line a day. The day-14 questions (rating, obstacle, whether the user
talked to someone) live on the look-back screen, not in proactive lines. The
wording and the exact surface rules belong to the web consumer
(`docs/ADR_MAM_RIVE_V2_CONTRACT.md` in the frontend); the server stores only
the answers.

### State

Journal/AI owns one `companion_states` document per owner (migration 026),
read and written by `/api/v1/companion-state` (contract 1.23.0):

- `focus`: goal enum and the local date it was chosen, or null.
- `chapters`: the 12 most recent chặng records keyed by local start date with
  optional goal, rating, obstacle and connected enums.
- `commitments`: the 30 most recent commitments (kind, minutes, due date,
  source, status, timestamps). At most three are OPEN (`COMMITMENT_LIMIT`);
  OPEN resolves once to DONE, NOT_YET or SWAPPED (`COMMITMENT_RESOLVED`); the
  oldest resolved one is trimmed first.
- `ui`: an opaque client guide string (at most 6,144 characters) and up to 300
  seen-moment keys with set semantics, oldest dropped first.

Writes are read-modify-write with an optimistic `revision`; the server retries
once on a concurrent write, then returns 409 `CONFLICT`.

### Privacy

- The whole state is one AES-256-GCM payload under `JOURNAL_ENCRYPTION_KEY`
  with AAD `${owner}:${revision}:companion-state`; only `_id`, owner, revision,
  `keyId` (the `JOURNAL_ENCRYPTION_KEY_ID` used) and `updatedAt` are plaintext.
- The state holds enums, dates and client UI keys only, never journal or chat
  text. The guide string is client UI state and must not carry free text.
- No AI provider reads or receives this state, so no AI consent level gates
  it; reading it needs only the owner's JWT. Payloads are never logged.
- Erasure: Journal/AI has no account-deletion or erasure consumer (ADR 0021,
  identity-account-deletion-retention-policy-v0.3). The state stays until such
  a consumer exists, which will delete the owner's document; this is the same
  named follow-up as companion conversations.

## Consequences

- Guide and moment state survive device changes; browser storage remains only
  a cache.
- The look-back can show self-reported associations from chapters and
  commitments; wording stays association, never causality.
- No Kafka event, no new configuration and no feature flag are added.
