# Contract proposals

Use this directory for future REST, Kafka, or WebSocket shapes that have not been accepted for implementation. A proposal must identify its owner, relevant requirement or ADR, open decisions, compatibility plan, and acceptance examples.

A proposal is not a callable API. Move an accepted REST contract into `contracts/openapi/` only as part of a reviewable implementation sequence, and mark each path `planned` or `implemented` according to `contracts/README.md`.

## Active proposals

| Proposal | Owner | Decision | Review state |
| --- | --- | --- | --- |
| [`care-support-plan-v1.yaml`](care-support-plan-v1.yaml) | Care | ADR 0013 / `mb-support-plan-selection-v1` | Policy-frozen server-proposal design; Care, Content, and Frontend contract review required |
| [`support-plan-companion-v1.md`](support-plan-companion-v1.md) | Care + Journal/AI | ADR 0040 / `MB-SUPPORT-PLAN-COMPANION-001` | MB-647 proposal overview, ownership, open delivery decisions, compatibility and acceptance examples |
| [`support-plan-companion-context-v1.schema.json`](support-plan-companion-context-v1.schema.json) | Care + Journal/AI | ADR 0040 / `MB-SUPPORT-PLAN-COMPANION-001` | Proposed closed model-context allowlist for MB-647 review; not a callable API |
| [`support-plan-companion-intent-v1.schema.json`](support-plan-companion-intent-v1.schema.json) | Care + Journal/AI | ADR 0040 / `MB-SUPPORT-PLAN-COMPANION-001` | Proposed deterministic intent envelope for MB-647 review; MB-649 owns runtime transport |
