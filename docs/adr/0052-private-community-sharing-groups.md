# Private Community sharing groups

Status: Accepted by the owner on 2026-11-07.

The owner requested private sharing groups in addition to existing chat groups
and selected invitation-only membership. Community remains the sole owner.

Chat groups retain their existing consent and messaging lifecycle. Sharing
groups have a separate member-only text feed and comments. Only accepted
friends can be invited; an invitation reveals the group name/description and
accepted count, never posts, comments or member identities. Invitees must
accept before content or membership queries. Owner removal and departure revoke
access immediately. Only the owner invites, removes others or archives; the
owner cannot leave an active group. Archived groups are unavailable.
Declined/removed memberships cannot be reinvited in this version.

Current caller/target eligibility is checked before local transactions. Local
profile restrictions and bilateral blocks govern identity/content visibility.
An owner block makes the group unavailable. Existing accepted membership
survives subsequent unfriending; pending acceptance still requires friendship.
No Care/Journal/AI data is read. No health matching or treatment group is claimed.

The additive REST contract is community-circles-v1.json. Migration 0025 adds
isolated circle, membership and text-entry tables. Existing feed/chat tables
are unchanged. Private text/name/description use the existing versioned
Community key ring and purpose-specific authenticated encryption. Responses
are no-store. Creation/publication use actor-scoped idempotency keys and
fingerprints. Commands serialize on caller profile/group locks. Deletion clears
entry ciphertext and keeps a content-free retry tombstone. Archival revokes
visibility without discarding prior membership facts. Text is not emitted to
Kafka, notifications, logs or URLs. This group-feed content is Community
publication, distinct from the chat-message retention policy.

This slice delivers text posts/comments, invitation decisions, membership
management, owner/author deletion and archival. Media, reactions, discovery,
join requests and realtime feed delivery are outside this version.

Delivery slices: (1) chat creation/member details and identity dialog UI;
(2) additive circle contract/migration/model/owner operations; (3) generated web
consumer and member-only feed; (4) packaging/migration/runtime verification.
No commit or Git publication is authorized by this implementation request.
