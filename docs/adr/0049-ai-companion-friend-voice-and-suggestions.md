# AI Companion friend voice, Text memory and suggestions

Prospective MB-806 consent amendment: [ADR 0050 / MB-TWO-LEVEL-AI-CONSENT-001](0050-two-level-ai-consent.md)
narrows existing current `AI_PROCESSING` v2 grants to L1 conversation and requires
new dependent `AI_CONTEXT_PROCESSING` v1 for personal sources, analysis and
accompaniment. Historical notices/decisions remain immutable. Voice retains L1
plus its separate unchanged Voice v2 grant and eligible empty-kind Text handoff.


- Decision ID: `MB-AI-COMPANION-FRIEND-001`
- Status: Accepted for MB-791 implementation by Product Owner, 2026-11-03. Runtime ships behind `JOURNAL_AI_COMPANION_GUIDANCE_V2_ENABLED`, default `false`.
- Owners: Journal/AI (guidance, memory, catalogue use, suggestion resolution), Content/Notification (published Resource catalogue), Web (button, routes, labels).
- Amends prospectively: [AI Companion policy v2](../policies/ai-companion-policy-v2.md) for TEXT chat only, and the daily token clause of [ADR 0021](0021-ai-companion-conversation-and-quota.md).
- Design: owner-approved MB-791 design (2026-11-03).

## Context

Text replies reflected and then asked a question every turn, gave no encouragement, and never pointed to MentalBridge. The provider saw only the current message, and the guidance had no persona or app knowledge.

## Decision

### Friend voice (TEXT only)

`companion-guidance-v2` and `companion-domain-knowledge-v2` apply only to TEXT while the flag is on. The companion talks like a warm long-time friend in Vietnamese, grounded only in what the user said.

It comforts first. While the user is sharing a feeling and has not asked what to do, it only comforts: no tip, exercise or MentalBridge offer. Once the user asks what to do or where to go, it encourages them and gives one small idea, with one MentalBridge action when that fits. Owner review of the real-provider replies set this rule, and comforting research supports it: unsolicited advice tends to read as a judgement that the person cannot cope. (Replaced for TEXT by the MB-845 and MB-846 amendments below.)

It avoids templated empathy. It does not open every reply by restating the situation and labelling the feeling, and it does not reuse a phrase from its earlier replies in the same conversation. It may use a light wish that takes the user's side for everyday troubles. It uses at most one emoji per reply, only now and then, and only in light moments, never on grief, distress, health or crisis.

It asks a question only when the question moves the conversation, and never ends two replies in a row with one (replaced for TEXT by the MB-845 and MB-846 amendments below). The v2 voice addresses the user as "bạn" and itself as "mình"; the flagged Mầm v3 uses "tớ" and "bạn".

It never minimises, jokes at the user or about grief, loss, self-worth, health or danger, sells features, or speaks as a clinician. Every v1 boundary stays: no diagnosis, medication, scoring, safety, eligibility or SupportPlan decisions. On a crisis cue it drops playfulness and offers, uses plain serious warmth, and points to Cần trợ giúp ngay. TEXT v2 uses temperature 0.7 on Bedrock and Gemini; OpenAI receives no temperature.

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

## F1 amendment — spoken friend persona, 2026-11-03

The owner selected the named Voice follow-up in the implementation session.
This prospectively adds an independent `JOURNAL_AI_VOICE_FRIEND_ENABLED` flag,
default `false`. The original TEXT delivery, approvals and historical Voice
campaigns remain under their original settings.

Voice uses a compact `companion-voice-friend-v1` prompt: natural Vietnamese,
comfort before advice, a small optional idea only on request, and useful specific
questions rather than a question after every reply. It avoids stock empathy,
invented personal facts, emoji, markup, URLs and navigation suggestions. Crisis
and clinical boundaries remain serious and non-clinical. This is a spoken
adaptation, not the long TEXT prompt or its catalogue/suggestion schema.

The existing current-call RAM context and explicit selected-Text handoff are
unchanged. Dual consent, entitlement, quota, lease, idle/session deadlines,
recording/export controls and retention are unchanged. The prompt version is
included in the existing Voice route binding.

Friend Voice counts the exact full system/schema/user/context input before
Bedrock inference, in pilot and non-pilot calls. More than 1,900 input tokens,
counting failure or missing authorization fails closed, without trimming context,
retry or fallback. The existing full-JSON output ceilings remain 300 tokens for
a default pilot, up to 600 for an explicitly approved pilot and 2,000 outside a
pilot. The 500-character spoken-message
schema, eight authority fields, provider/voice pins, temperature and END_TURN-only
completion requirement remain intact. With the flag off, legacy Voice and both
TEXT request variants remain unchanged.

Source tests prove request/validation boundaries, not model tone quality. Enable
the flag only after a separately approved, credit-capped listening/evaluation
window on the real Voice route, safety/clinical/privacy checks and owner tone
sign-off. The earlier TEXT run 11 does not approve this new spoken persona.
Applying this amendment does not open a campaign, microphone or provider call.

## MB-845 amendment — caring follow-up arc (TEXT), 2026-11-14

The owner chose this change after reviewing comfort-only replies. For the TEXT
friend voice only, it replaces the "only comfort until asked" rule and the "ask
only when it moves the conversation, never two replies in a row" rule above.

When the user vents about a hard moment, the first two replies comfort and end
with exactly one short follow-up question about a detail the user gave, building
on their last answer. The third reply wraps up; it may ask once more only when
every answer so far was a word or two. By the fourth reply at the latest, after
three question replies in a row, or at once when the user asks what to do or has
said what they wanted, the reply wraps up: with no question at all, it briefly
reflects what it understood, comforts, and gives one or two gentle, concrete
ideas (for grief or self-blame, a gentle way to let the feeling be). Explore replies give no tip, exercise
or offer. A wrap-up may softly name one feature or resource; that alone does not
set `suggestion`, which still needs an accepted offer or a direct request.

A crisis cue overrides the arc: the fixed crisis reply and its null suggestion
are unchanged. Templated-empathy, emoji, length, clinical and authority rules,
the shared v1 prompt and Voice are unchanged. The guidance label stays
`companion-guidance-v2`; evaluation evidence identifies each prompt by SHA-256.
The rollout gate above still applies before the flag is switched on. Evidence:
[MB-845 companion evaluation](../story-mb-845-companion-eval.md).

## MB-869 amendment — reply length by package, 2026-11-17

The owner decided on 2026-11-17 that short replies apply only to `FREE`. This
replaces "length rules are unchanged" above for the cases below.

- TEXT `FREE`: unchanged. v1 asks for at most 500 characters, the friend v2
  guidance for 1–4 short sentences under 400 characters, and the provider schema
  and server validator cap the message at 500 characters.
- TEXT `PLUS` and `PREMIUM`: 2–6 sentences and at most 900 characters, for v1 and
  the friend v2 guidance alike. The provider schema `maxLength` and the server
  validator rise to 900 on this path only. Only the length clause changes; tone,
  safety, crisis and authority text stay byte-identical. The public
  `SendAiMessageResponse.assistant` limit becomes 900.
- Voice (paid only): without a pilot `replyStyle`, every call uses the
  SUPPORTIVE delivery, two or three natural sentences of about 180–280
  characters. An explicit pilot `replyStyle` still wins, so `BRIEF` stays
  available. The 500-character spoken message limit and the ADR 0046
  1,000-character ceiling are unchanged, as are pilot token caps.

The package comes from the existing Consultation entitlement decision; no new
package value or entitlement call is added.

## MB-846 amendment — Mầm TEXT response mode, 2026-11-22 (owner wording review pending)

The owner approved implementation of a separate, disabled-by-default
`JOURNAL_AI_COMPANION_MAM_TEXT_V3_ENABLED` variant. It uses
`companion-mam-text-v3` and retains the v2 consent-filtered history, catalogue,
suggestion schema, provider route, retry, quota and crisis pointer. With this
flag off, v2 requests are unchanged. Voice is unchanged.

For each Mầm TEXT turn, Journal/AI selects `LISTEN`, `CLARIFY`,
`SUPPORT_AND_WRAP_UP`, `BOUNDARY` or `SAFETY` from the current user message and
the roles and text of the last eight messages of the same conversation. The
count does not depend on the byte-capped history sent to the provider, so long
messages still reach wrap-up; only the mode leaves the server. The selection is sent as an
explicit provider directive and counted in the preflight token estimate. It
is not a public API field, persisted record, safety diagnosis or entitlement
decision. A direct request for practical help wraps up immediately; an explicit
code or homework request gets a scope boundary; crisis cues take priority.
Otherwise the first two replies in a venting arc listen and comfort, and the
third wraps up. `CLARIFY` is chosen only on a missing-information signal
(every answer so far is a word or two) or when the user asks to explore. The
topic count is rebuilt from the user turns alone: it restarts after a turn
whose mode closed it (wrap-up, boundary, safety) or after a thanks, agreement
or feeling-better message. The lexical crisis rule matches accented
phrases on the original text, and unaccented phrases only in messages typed
without diacritics, so words such as “từ từ” never trigger it. It is a
heuristic, not a Care safety determination: the provider's existing crisis
instruction still applies when it misses a cue, and every other mode's
directive states that this crisis rule overrides it.

`SUPPORT_AND_WRAP_UP` briefly reflects the user's details, comforts, then
gives one or two concrete practical steps fitted to those details. It ends
without a question unless information critical for immediate safety is
missing. `BOUNDARY` supports emotions and how to approach a problem; it does
not write code or complete homework. Mầm says “tớ” to “bạn”, varies openers,
avoids templated empathy, uses only occasional fitting emoji, and keeps
replies to 1,500 characters. While the flag is on, the 1,500-character limit
applies to every package and supersedes the MB-869 900-character paid limit.
The server enforces it on every reply (an over-limit reply is retried once,
then refused); the output schema also carries it where the provider supports
it, which Bedrock does not. Humour scales with distress: playful teasing only
on mild, everyday stress, never on grief, loss, abuse, self-blame, severe
distress or a crisis cue.
`SAFETY` retains the existing fixed crisis reply, and the server drops any
suggestion on a `SAFETY` turn. The
earlier MB-845 question/advice rule remains the v2 rollback behavior.

MB-846b follow-up, 2026-11-22 (owner wording review pending): replies vary in
length with the moment, from one short sentence to a few short paragraphs
under the 1,500-character limit, and Mầm asks no follow-up question by
default. The prompt states: “Do not ask follow-up questions by default. When
sufficient context exists, provide helpful guidance and end naturally. Ask at
most ONE question, only when essential information is missing or the user
explicitly wants to explore further. Do not optimize for conversation
length.” Only `CLARIFY` may end with one question; `LISTEN` comforts without
one. This supersedes, for Mầm TEXT v3 only, the MB-845 rule that the first
replies of a venting arc end with a question. Mầm never claims to remember
anything outside the history it was given. Because deep replies reach 1,500 characters, Mầm
sends the last 20 same-conversation messages within 16,000 bytes (v2 keeps
8 messages within 3,000 bytes), and one provider attempt may take up to 18
seconds inside the unchanged 24-second send deadline.

The real Sonnet 4.6 gate is wrap-up ≥0.80 per turn, explore ≥0.80 per turn
under the MB-846b definition (no question, or one question only where the
dataset marks essential information as missing, where a missing question
also fails), no provider error (a reply over 1,500 characters is one), and
crisis pointer 1.0. Before the flag goes on, the web and mobile send-response
validators must accept 1,500 characters (frontend follow-up; they stop at 900
and 500 today). The rollout gate and owner
tone review above still apply; this amendment is pending owner wording
approval before activation.
