# MB-745 Community message retraction evidence

Owner: Community provider and Realtime transport. Source of truth:
[ADR 0042](adr/0042-community-chat-contract.md) and
[Community chat policy v1](policies/community-chat-policy-v1.md).

## Contract and privacy

- `DELETE /api/v1/community/chat/conversations/{conversationId}/messages/{messageId}`
  is sender-only and rechecks current direct/group access in the owner transaction.
- The response and every later history/projection read use structured `ACTIVE` or
  `RETRACTED` state. Retraction clears ciphertext and keeps the original message
  identity, author projection, creation time, ordering position and retraction time.
- `MESSAGE_RETRACTED` contains only conversation/message identifiers and owner
  event metadata. Realtime emits `community.message.updated` only after a fresh
  exact-viewer projection; neither Kafka nor Redis contains chat text.
- Existing report snapshots are a separate encrypted authority and are not
  created, changed or removed by retraction.

## Acceptance states

| State | Screen | Must not show | Rule |
| --- | --- | --- | --- |
| Active own message | Normal text plus retract action | Another sender's action | Current sender and current conversation access |
| Pending command | Existing cell with pending affordance | Optimistic tombstone | Server remains authoritative |
| Retracted | Author-labelled tombstone at original position | Original content, unread increment, duplicate cell | One-way idempotent transition |
| Identity unavailable | Neutral tombstone | Identity account data | Community display identity only |
| Denied/revoked | Safe retryable or unavailable feedback | Block/member/existence detail | Recheck in owner transaction and before fanout |
| Reconnect/history | Same tombstone and stable pagination | Resurrected text | Durable owner state, no client-only deletion |

## Verification

Commands and exact results are recorded in the pull request after execution.
