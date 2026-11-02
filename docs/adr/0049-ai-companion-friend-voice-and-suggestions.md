# AI Companion friend voice, Text memory and suggestions

- Decision ID: `MB-AI-COMPANION-FRIEND-001`
- Status: Accepted for MB-791 implementation by Product Owner, 2026-11-03. Runtime ships behind `JOURNAL_AI_COMPANION_GUIDANCE_V2_ENABLED`, default `false`.
- Owners: Journal/AI (guidance, memory, catalogue use, suggestion resolution), Content/Notification (published Resource catalogue), Web (button, routes, labels).
- Amends prospectively: [AI Companion policy v2](../policies/ai-companion-policy-v2.md) for TEXT chat only, and the daily token clause of [ADR 0021](0021-ai-companion-conversation-and-quota.md).
- Design: owner-approved MB-791 design (2026-11-03).

## Context

Text replies reflected and then asked a question every turn, gave no encouragement, and never pointed to MentalBridge. The provider saw only the current message, and the guidance had no persona or app knowledge.

## Decision

### Friend voice (TEXT only)

`companion-guidance-v2` and `companion-domain-knowledge-v2` apply only to TEXT while the flag is on. The companion talks like a warm long-time friend in Vietnamese. It comforts and encourages, grounded only in what the user said, and may give one small practical idea or one MentalBridge offer. It asks a question only when the question moves the conversation, and never ends two replies in a row with one. It addresses the user as "bạn" and itself as "mình", mirrors tui/cậu/bà/ông lightly, and never uses mày/tao.

It never minimises, jokes about the problem, sells features, or speaks as a clinician. Every v1 boundary stays: no diagnosis, medication, scoring, safety, eligibility or SupportPlan decisions. On a crisis cue it drops playfulness and offers, uses plain serious warmth, and points to Cần trợ giúp ngay. TEXT v2 uses temperature 0.7 on Bedrock and Gemini; OpenAI receives no temperature.

Voice prompt, schema, temperature and token caps are unchanged. With the flag off, every provider request is byte-identical to v1.

### Text conversation memory

The provider receives up to the last 8 stored messages of the same conversation, oldest first, keeping the newest that fit in 3,000 UTF-8 bytes. A message whose `contextKinds` includes a kind the conversation no longer selects is left out, so withdrawn Journal, plan, screening or resource context leaves memory. History is sent as untrusted data, like context. Nothing new is persisted, and AI_PROCESSING consent already covers the owner's own conversation.

### Non-personal Resource catalogue

Journal/AI reads at most 30 published reviewed Resources from the public Content list (`locale=vi-VN`), with no user token. The list is cached in memory for 10 minutes; failures are not cached and give an empty catalogue. The provider sees only `ref` (`R1`…), category and title, within 1,500 bytes. It is the same for every user and carries no personal data.

### Closed-enum suggestion

The TEXT v2 output adds a nullable `suggestion` of `{ destination, resourceRef }`. The destination is one of `RESOURCE`, `RESOURCE_LIBRARY`, `JOURNAL`, `EMOTION_CHECK_IN`, `SUPPORT_PLAN` or `SPECIALIST_DISCOVERY`. The model chooses the destination, and may set it only when the user accepted an offer or directly asked what to do or where to go. The server then resolves `resourceRef` to the real Resource id and title. The user decides by pressing the button or not.

An unknown ref, a RESOURCE without a ref, a ref on another destination, an unknown destination or a malformed object becomes `null`; the reply still succeeds. The model never writes URLs, routes, ids or labels. A v2 message containing a URL, `www.`, HTML markup or a Markdown link is an invalid response. The contract carries no href and no label; the web owns both.

Offering a published reviewed Resource or an app area is not deciding resource eligibility or package access. The destination page performs its own access and package checks.

### Encrypted suggestion persistence

The resolved `{ destination, resourceId, resourceTitle }` is stored on the ASSISTANT message, encrypted with the same per-owner AES-256-GCM key as `content`. Its AAD is `<owner>:<conversation>:<message>:suggestion`, so it cannot be opened as message content. This keeps the ADR 0021 rule that plaintext persistence holds only bounded context-kind labels. Conversation reads decrypt it, so the button survives reload. Migration `017_companion_message_suggestion` allows the encrypted field; its rollback refuses once any suggestion exists.

The idempotent command replay snapshot stores `suggestion: null`; it never holds the resolved destination, Resource ID or title. An exact replay restores the suggestion by decrypting it from the original assistant message, with no provider call or quota charge. A suggestion envelope that fails authentication or parsing reads as `null` and never blocks the conversation; message content stays strict.

### Per-plan token budget (`companion-quota-v2`)

The daily provider token budget is per plan: FREE 100,000, PLUS 250,000, PREMIUM 600,000 by default. Each is configurable between 1,000 and 10,000,000. Before the provider call, the server estimates input tokens as `ceil(UTF-8 bytes / 4)` of the exact system text and user payload. It refuses with 429 `CHAT_TOKEN_BUDGET_EXHAUSTED` when the estimate would exceed the remaining budget, so a refused request spends nothing. This applies whether or not the flag is on. Quota responses report `companion-quota-v2`; replays recorded earlier may still report v1.

## Rollout gate

Switching the flag on needs all of these:

- migration 017 applied with owner authorization;
- a real-provider run of the synthetic conversation eval on the live TEXT route (Bedrock Sonnet 4.6), with a credit cap approved by the owner;
- zero safety, clinical, URL or crisis failures, and a human rubric average of at least 4/5 on each dimension;
- owner sign-off.

The signed-off run is the approval evidence for a bumped `PROVIDER_APPROVAL_VERSION`; that bump happens at switch-on, not in this change.

## Consequences

- Voice keeps the v1 tone; a friend voice for Voice is a named follow-up within its 1,900-token cap.
- The first 30 published Resources are not relevance-ranked; ranking or filtering is a follow-up once the catalogue grows.
- Web must accept quota v2 and `suggestion` before the backend deploys.
