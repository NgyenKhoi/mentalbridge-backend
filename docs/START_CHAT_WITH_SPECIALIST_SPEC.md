# Start Chat with Specialist

> **Implemented by MB-382.** This document is the canonical appointment-scoped
> chat boundary for the web waiting room, active session, reconnect/resync, and
> retained read-only history flow.

## Function trigger

An authenticated User opens a conversation selected from an eligible confirmed
`IN_APP_CHAT` appointment. The server derives actor identity from the trusted
session; client-supplied appointment and conversation IDs are selectors only.

## Required behavior

- Resolve exactly one conversation for an eligible confirmed appointment.
- Authorize both participants before history, presence, join, or send access.
- Allow waiting-room join from ten minutes before the authoritative start, but
  allow sending only in `[startsAt, endsAt)` while the appointment remains
  session-eligible.
- Return bounded, cursor-based, authorized message history.
- Treat presence as ephemeral display data, never as authorization.
- Do not enable unrestricted direct, 24/7, emergency, or guaranteed-specialist
  messaging.

## Boundary and data rules

| Concern | Rule |
| --- | --- |
| Identity | Use the authenticated server-side actor and effective role. |
| Eligibility | Require an approved Specialist, confirmed appointment, `IN_APP_CHAT` channel, and valid window. |
| Authorization | A non-participant receives a non-disclosing denial; no conversation metadata or message data is returned. |
| History | Use stable bounded cursor pagination scoped to the authorized conversation. |
| Safety | Do not claim monitoring, emergency dispatch, or live availability from presence. |
| Sensitive data | Do not expose ciphertext, encryption keys, tokens, private URLs, raw message logs, or internal moderation state. |

## Failure behavior

| Situation | Required behavior |
| --- | --- |
| Missing/expired session | Redirect to sign-in; disclose no conversation data. |
| Invalid selector or unauthorized participant | Fail closed with a non-disclosing response. |
| Ineligible, cancelled, replaced by a reschedule request, or out-of-window appointment | Waiting entry is allowed only from ten minutes before start; sending is allowed only in `[startsAt, endsAt)`; afterward render retained history read-only where policy permits. |
| History dependency unavailable | Render an explicit error and allow a safe retry without stale data presented as current. |
| Live connection unavailable | Preserve already authorized history, state that sending is unavailable, and do not show a false sent/online state. |
| Presence unavailable | Display unknown presence without changing authorization. |

## Runtime status and sources

- Realtime Service owns realtime authentication, persistence, presence, and
  transport contracts.
- Consultation owns appointment eligibility and participant authority.
- MB-382 implements the frontend chat journey and production appointment
  integration; receipts, cross-instance fan-out, attachments, moderation, and
  video remain separately owned capabilities.
- The authoritative runtime contracts are the versioned Realtime REST/OpenAPI
  and WebSocket JSON Schema artifacts, plus the owning service implementation.
