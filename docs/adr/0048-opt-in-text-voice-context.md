# Opt-in selected Text context for Voice

Prospective MB-806 consent amendment: [ADR 0050 / MB-TWO-LEVEL-AI-CONSENT-001](0050-two-level-ai-consent.md)
narrows existing current `AI_PROCESSING` v2 grants to L1 conversation and requires
new dependent `AI_CONTEXT_PROCESSING` v1 for personal sources, analysis and
accompaniment. Historical notices/decisions remain immutable. Voice retains L1
plus its separate unchanged Voice v2 grant and eligible empty-kind Text handoff.


- Decision ID: `MB-TEXT-VOICE-HANDOFF-001`
- Status: Accepted for MB-790 implementation by Product Owner, 2026-11-02.
- Owners: Journal/AI (context and authorization), Care (consent), Web (choice).
- Supersedes prospectively only the no-Text-history clause of
  [MB-AI-VOICE-COMPANION-001](0046-ai-voice-companion-contract.md).

## Decision

Voice can continue the explicitly selected, owner-authorized Text conversation.
The choice is off by default for each call. Voice-only remains available. It
does not authorize Journal, screening, SupportPlan, resource or other private
history retrieval. Journal/AI uses the MB-789 shared guidance with channel style
and untrusted context kept separate from system instructions.

Care publishes `ai-voice-processing-capstone-v2`. Every Voice user must accept
this current notice before their next call, even for voice-only calls. Existing
AI_PROCESSING remains unchanged and is also required. Historical Voice v1 grants
and exact idempotent replays retain their original version and are not current;
they are never relabeled or migrated to v2. Reusing a key with a different
version remains a conflict. Revocation still stops processing and clears RAM.

## Bounded handoff

The server selects at most two latest complete adjacent USER/ASSISTANT pairs,
at most 1,200 UTF-8 bytes including JSON framing. Both messages must have empty
`contextKinds`. Selection stops at an unsafe or oversized pair instead of
silently reaching back to an older topic. No LLM summary is generated.

An owner-bound signed ticket contains selectors, snapshot hash, nonce, version
and a fixed 120-second expiry, never message content. Preparation checks current
eligibility and does not reserve Voice quota. At admission, Journal verifies
ownership, unexpired conversation, unchanged snapshot and both consents again.
The ledger consumes the ticket hash atomically with session/quota admission;
one ticket cannot authorize a different session, key or owner, including after
settlement. An exact accepted start retry preserves the original intent.

The selected seed remains in the issuing process RAM, then moves once into
the authorized Voice context. It shares the existing four-pair/2,000-byte
context budget with current-call turns. Oldest selected pairs are trimmed first
when necessary; the runtime input-token authorization remains authoritative.
Termination, consent loss, expiry and shutdown purge context. Restart or routing
to another process without that RAM fails closed; there is no database content
fallback. Deployment must retain process affinity for handoff preparation,
admission and turns. The pilot remains limited to two concurrent calls.

## Compatibility and rollout

Migration 016 adds only an optional 43-character HMAC reference and a partial
unique index to Voice sessions. It preserves creation-bound 30-day metadata
retention, quota, entitlement, prices, legacy empty-start fingerprints and
historical campaigns. Down migration refuses while bound session rows exist;
disable the feature for rollback and retain the compatible schema until those
rows expire through the normal reconciler.

Deploy the owner migration and coordinated Care/Journal contracts before Web.
`JOURNAL_AI_VOICE_TEXT_HANDOFF_ENABLED=false` is the default. Enabling it does
not enable Voice admission, providers or agents. Old Voice v1 clients must
refresh to obtain and accept the v2 disclosure. Web sends only the selected ID,
then the opaque ticket; it never supplies a summary or message array. An unknown
admission outcome retries only the original key/body, never silently falls back
to a new voice-only call. Definite preparation failure offers an explicit
voice-only action. Session `contextMode` confirms preparation, not model use.

This decision authorizes implementation, not a new live campaign, staging or
merge. Recording, transcript persistence, content logging and exports remain
off. Provider routing, quota ceilings and safety decisions are unchanged.
