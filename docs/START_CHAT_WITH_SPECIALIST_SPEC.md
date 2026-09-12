# Start Chat with Specialist

> **Definition-only specification.** This document describes the approved
> appointment-scoped chat boundary. It does not claim that the web chat UI,
> consultation eligibility flow, or production conversation runtime is
> delivered. Realtime foundation work is separately owned and production chat
> eligibility remains unavailable until its contract and approval gates pass.

## Function trigger

An authenticated User opens a conversation selected from an eligible confirmed
`IN_APP_CHAT` appointment. The server derives actor identity from the trusted
session; client-supplied appointment and conversation IDs are selectors only.

## Required behavior

- Resolve exactly one conversation for an eligible confirmed appointment.
- Authorize both participants before history, presence, join, or send access.
- Allow live join and sending only within the authoritative appointment window.
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
| Ineligible, cancelled, rescheduled, or out-of-window appointment | Do not join or send; render an explicit unavailable/read-only state only where policy permits. |
| History dependency unavailable | Render an explicit error and allow a safe retry without stale data presented as current. |
| Live connection unavailable | Preserve already authorized history, state that sending is unavailable, and do not show a false sent/online state. |
| Presence unavailable | Display unknown presence without changing authorization. |

## Runtime status and sources

- Realtime Service owns realtime authentication, persistence, presence, and
  transport contracts.
- Consultation owns appointment eligibility and participant authority.
- The frontend chat journey and production appointment integration remain
  outside Sprint 2 release evidence.
- The authoritative runtime contracts are the versioned Realtime REST/OpenAPI
  and WebSocket JSON Schema artifacts, plus the owning service implementation.
