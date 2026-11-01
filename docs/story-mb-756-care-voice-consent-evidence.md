# MB-756 Care Voice prerequisite

Story: [MB-756](https://vunguyenkhoi47.atlassian.net/browse/MB-756).
This is the first of three owner-specific PRs: Care, Consultation, Journal/AI.
Baseline at publication preparation: `463a15ee` from `origin/dev`.

Care implements the immutable Vietnamese Voice notice and independent
`AI_VOICE_PROCESSING` / `ai-voice-processing-capstone-v1` decisions from
[ADR 0046](adr/0046-ai-voice-companion-contract.md). Grant/withdrawal is
append-only, owner-scoped and replay-safe. Current Voice consent and the
service-only dual-consent authorization read are separate from existing text
consent. Text users keep their existing consent and generic two-stream API.

The four implemented operations are in the active
[Care OpenAPI](../contracts/openapi/care-service-v1.yaml). The accepted
MB-755 proposal stays frozen; other Voice operations remain planned.
An internal caller needs a unique purpose-scoped credential, exact subject,
session and purpose; a worker header or user bearer alone cannot authorize it.
New Voice boundaries reject unknown fields, duplicate/trailing JSON, invalid
types, unsupported transport and malformed identifiers with sanitized closed
Problem Details and `no-store`.

Migration `032-ai-voice-processing-consent.sql` appends changeset
`care-033-ai-voice-processing-consent`, widens only the consent purpose CHECK,
and preserves previous rows and migration checksums. The paired
[Care logical model](domain-model/relational/care-service.sql) and
[dictionary](database/postgresql/care-service.md) explain the new stream.
Voice usage retention belongs to Journal/AI; Care consent history keeps its
existing retention policy.

Focused local verification used JDK 21, cached Maven 3.9.16 and disposable
PostgreSQL Testcontainers with dotenv disabled. Latest reports across the
checkpoints contain 43 passing tests in nine classes, including real database
concurrency, migration upgrade/no-op replay, frozen contracts and legacy text
consent regressions. The earlier string-to-boolean boundary failure was fixed;
the final boundary-only run passed 5 tests, and the contract/upgrade run passed
2 tests. These are accumulated focused checks, not one full Care suite run.
The exact commands and required final-head CI results belong to the PR.

No live provider call, shared database migration, microphone test or deployment
was performed. This prerequisite cannot create a Voice room or spend Voice,
text or Consultation quota. Journal/AI integration verification follows only
after both prerequisite PRs are merged to `dev`.
