# Voice measurement campaign field dictionary — MB-862

Source approval: MB-862 #10461. Runtime truth: append-only migration 020.
Logical model: [Journal measurement campaign](../domain-model/document/voice-measurement-campaign.md).
No migration or runtime command was executed on shared dev/staging data.

| Field                                                 | Meaning and consistency                                                                                                                                                                                      |
| ----------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `_id`                                                 | Explicit approved campaign UUID; provision once, never reset/recreate to retry.                                                                                                                              |
| `configHash`                                          | SHA-256 of versioned canonical window, sorted allowlist, caps, allocations and current pinned execution settings; mismatches deny. No raw allowlist or secret is stored.                                     |
| `startsAt`, `endsAt`                                  | Immutable UTC approved window; maximum 30 minutes. Missing/expired state denies admission and work.                                                                                                          |
| `caps`                                                | Sessions ≤2, concurrent=1, total turns ≤12, conservative wall voice seconds ≤480, TTS characters ≤4,000. Operational approval may be stricter than product policy.                                           |
| `allocation`                                          | Fixed floor partition of total limits by maximum sessions, additionally reduced by existing pilot turns/time. Unused allocation is not transferred.                                                          |
| `reservations`                                        | At most two consumed admission allocations, including cancelled/failed/unknown attempts. Never deleted or decremented by quota settlement.                                                                   |
| `reservations.voiceSessionId`                         | Owner-service reference, unique across campaigns; no account identity.                                                                                                                                       |
| `reservations.createdAt`, `releasedAt`                | Admission and confirmed concurrency-release instants. Null release means held, not timed out.                                                                                                                |
| `reservations.workerClaimed`                          | One-shot claim; restart/unknown response cannot obtain a fresh media budget.                                                                                                                                 |
| `reservations.turns`, `voiceSeconds`, `ttsCharacters` | Full irrevocable allocated spend, not observed invoice/usage. Voice seconds conservatively reserve full admitted session wall ceiling, including connecting, rather than only product-charge ACTIVE seconds. |
| `reservations.turnReservations`                       | Bounded turn UUID and numeric sequence, consumed before inference; no content.                                                                                                                               |
| `reservations.speechReservations`                     | Bounded turn/reply UUIDs and UTF-16 character count, reserved before publication; failed/interrupted/unknown delivery keeps spend.                                                                           |
| `createdAt`, `updatedAt`, `expiresAt`                 | UTC instants; expires 30 days from creation. TTL cannot extend an approval or restore an expired manifest.                                                                                                   |

`uq_measurement_session` is a unique partial multikey index excluding empty
reservations. `ttl_voice_measurement_campaign` deletes expired metadata. Migration
down refuses while records remain; application rollback must not reuse campaign
settings on an old/unbound writer. Shared fleet activation needs separate review,
exact revisions and configuration binding; source approval alone permits none.
