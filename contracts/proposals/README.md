# Contract proposals

Use this directory for future REST, Kafka, or WebSocket shapes that have not been accepted for implementation. A proposal must identify its owner, relevant requirement or ADR, open decisions, compatibility plan, and acceptance examples.

A proposal is not a callable API. Move an accepted REST contract into `contracts/openapi/` only as part of a reviewable implementation sequence, and mark each path `planned` or `implemented` according to `contracts/README.md`.

## Active proposals

MB-755 publishes the accepted target, still planned for runtime:

- [Voice REST/OpenAPI v1](companion-voice-v1.openapi.json), including Care voice
  disclosure/decisions and new Journal-only owner authorization prerequisites;
- [closed owner/worker envelopes](companion-voice-worker-v1.schema.json);
- [frozen policy schema](companion-voice-policy-v1.schema.json), validating the
  [approved quota/privacy snapshot](../../docs/policies/companion-voice-policy-v1.json);
- [nine-state lifecycle](companion-voice-lifecycle-v1.json) and
  [synthetic positive/negative fixtures](companion-voice-v1.examples.json);
- [executable contract tests](companion-voice-contract.test.mjs), run locally and
  in the Journal/AI CI job; they prove schema/design consistency, not deployed
  quota, media or provider behavior.

See [ADR 0046](../../docs/adr/0046-ai-voice-companion-contract.md),
[Voice policy](../../docs/policies/companion-voice-policy-v1.md),
[demo runbook](../../docs/ai-voice-demo-runbook.md) and
[MB-755 evidence](../../docs/story-mb-755-ai-voice-contract-evidence.md).

MB-684 adds the [Community chat OpenAPI candidate](community-chat-v1.openapi.json),
[client commands](community-chat-command-v1.schema.json),
[namespace handshake](community-chat-handshake-v1.schema.json),
[private socket events](community-chat-realtime-v1.schema.json),
[minimized durable facts](community-chat-fact-v1.schema.json) and
[synthetic examples](community-chat-v1.examples.json).
[ADR 0042 / MB-COMMUNITY-CHAT-001](../../docs/adr/0042-community-chat-contract.md)
requires owner/security approval before downstream runtime work. Every route
is planned. Acceptance states, permission matrix, approval items and runtime
verification obligations are in the
[MB-684 evidence](../../docs/story-mb-684-community-chat-contract-evidence.md).

| Proposal | Owner | Decision | Review state |
| --- | --- | --- | --- |
| [`care-support-plan-v1.yaml`](care-support-plan-v1.yaml) | Care | ADR 0013 / `mb-support-plan-selection-v1` | Policy-frozen server-proposal design; Care, Content, and Frontend contract review required |
| [`support-plan-companion-v1.md`](support-plan-companion-v1.md) | Care + Journal/AI | ADR 0040 / `MB-SUPPORT-PLAN-COMPANION-001` | MB-647 proposal overview, ownership, open delivery decisions, compatibility and acceptance examples |
| [`support-plan-companion-context-v1.schema.json`](support-plan-companion-context-v1.schema.json) | Care + Journal/AI | ADR 0040 / `MB-SUPPORT-PLAN-COMPANION-001` | Proposed closed model-context allowlist for MB-647 review; not a callable API |
| [`support-plan-companion-intent-v1.schema.json`](support-plan-companion-intent-v1.schema.json) | Care + Journal/AI | ADR 0040 / `MB-SUPPORT-PLAN-COMPANION-001` | Proposed deterministic intent envelope for MB-647 review; MB-649 owns runtime transport |
