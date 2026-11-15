# ADR 0051 — Community friendships and member pages

Accepted by the project owner in this session on 2026-11-06.

Community owns reciprocal friendships and Community-only member pages. The
owner selected friends-only admission for new direct conversations and group
invitations while preserving existing active conversations and memberships.
This prospectively amends ADR 0042 and community-chat-policy-v1 when
`COMMUNITY_FRIENDS_ENABLED=true`. Disabled preserves the previous rollout.

One unordered profile pair has one pending or accepted friendship. Sending a
crossed request returns the existing incoming request and never accepts it.
Only its recipient can accept. Either participant can delete the relationship,
which cancels, declines or unfriends according to its current state. Removal
deletes the current relationship, without importing or deleting chat history.
The request UUID distinguishes a later new request from an earlier one.
Commands lock the same sorted Community profile rows as chat and blocks.
There are no friendship events, public friend lists or public friend counts.

Accepted friendship is explicit reciprocal consent to start direct messaging.
New direct starts activate the existing unordered conversation, including a
previously pending/declined request, after current account and block checks.
Old request records remain historical. Existing active direct conversations
remain usable after unfriending. New group invitations and pending invitation
acceptance require a current friendship with the inviter. Existing group
memberships remain usable. Blocking, restriction and current account-state
checks retain their existing precedence.

Member pages expose only current Community display identity and visible named
posts. Anonymous publications are excluded even from the author's own member
page. Email, Identity subject, health records, saved items, chat history, friend
lists and online status are absent. A block in either direction, a deleted
profile or a local access restriction hides the member surface. Search requires
an explicit query of 2–80 code points and returns bounded current display names.
It never discovers users through Identity attributes or clinical data.

Apply additive migration 0024 before enabling the feature. The web consumer
regenerates Community types from the provider contract and uses the existing
session BFF. Rollback disables the feature and retains relationship rows and
existing versioned chat encryption keys. Key material must never be replaced
while retained ciphertext uses that key version.
