# Contract proposals

Use this directory for future REST, Kafka, or WebSocket shapes that have not been accepted for implementation. A proposal must identify its owner, relevant requirement or ADR, open decisions, compatibility plan, and acceptance examples.

A proposal is not a callable API. Move an accepted REST contract into `contracts/openapi/` only as part of a reviewable implementation sequence, and mark each path `planned` or `implemented` according to `contracts/README.md`.

## Active proposals

| Proposal | Owner | Decision | Review state |
| --- | --- | --- | --- |
| [`care-support-plan-v1.yaml`](care-support-plan-v1.yaml) | Care | ADR 0013 / `mb-support-plan-selection-v1` | Policy-frozen server-proposal design; Care, Content, and Frontend contract review required |
