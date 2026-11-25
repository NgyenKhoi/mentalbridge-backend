# MB-755 AI Voice contract evidence

Story: [MB-755](https://vunguyenkhoi47.atlassian.net/browse/MB-755), parent MB-754.
Branch: `docs/journal-ai-voice-contract-(MB-755)`.
Initial baseline: `11f9b6b4f40bf9df001a39bea98694bcd3400ca3` from `origin/dev`.

## Approved decisions

The owner explicitly approved the following in the implementation conversation
on 2026-10-23 before policy/contracts were authored:

| Decision       | Approved target                                                                                                                                                 |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FREE           | Text only; no voice                                                                                                                                             |
| PLUS           | 2 voice sessions/day, 300 seconds/session maximum, 600 seconds/day                                                                                              |
| PREMIUM        | 3 voice sessions/day, 600 seconds/session maximum, 1,800 seconds/day                                                                                            |
| Day and timing | Asia/Ho_Chi_Minh midnight reset, no rollover; 60-second idle timeout; 30-second reconnect grace without extending the hard session deadline                     |
| Accounting     | Refund all reserved usage if no first successful spoken reply; thereafter charge only eligible ACTIVE time, excluding CONNECTING/RECONNECTING                   |
| Changes        | A midnight-crossing session keeps its starting day; upgrade raises the cap without resetting usage; loss of entitlement or either consent stops voice           |
| Consent        | Care-owned independent AI_VOICE_PROCESSING, required alongside current AI_PROCESSING; text users do not reconsent just because voice is added                   |
| Privacy        | No recording/transcript persistence or content observability; current-call RAM context only; no stored Journal or text history; 30-day minimized usage metadata |
| Infrastructure | Existing mentalbridge LiveKit Cloud Free project, hosted Node agent and Inference; existing Journal/AI Bedrock adapter; no extra VPS/EC2                        |
| Bedrock        | ap-southeast-1, approved Sonnet 4.6/Opus 4.6 candidates and global profiles; disclosure explicitly allows cross-region processing                               |
| CI exception   | One conditional Journal-only workflow step may run tests stored in contracts/; no runtime, dependency or lockfile edits                                         |

The earlier illustrative quotas were not treated as approval. The values above
come from the owner's explicit selection. Speech candidates remain subject to
MB-760 evaluation; free allowance, model access, hosted region and real call
quality have not been verified against the account.

## Artifacts and acceptance

| Acceptance criterion                                      | Evidence                                                                                                                                                                                                   |
| --------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Accepted ADR, finite policy and security/privacy boundary | [ADR 0046](adr/0046-ai-voice-companion-contract.md), [policy and Vietnamese disclosure](policies/companion-voice-policy-v1.md), [machine policy](policies/companion-voice-policy-v1.json)                  |
| Versioned REST and worker handoff                         | [OpenAPI v1](../contracts/proposals/companion-voice-v1.openapi.json), [closed worker schema](../contracts/proposals/companion-voice-worker-v1.schema.json), all endpoints planned with explicit owner/auth |
| Session lifecycle, errors and acceptance cases            | [Nine-state transition contract](../contracts/proposals/companion-voice-lifecycle-v1.json), typed Problem Details and [synthetic cases](../contracts/proposals/companion-voice-v1.examples.json)           |
| Consultation remains entitlement owner                    | New dedicated read-only owner check is planned; existing financial/credit/appointment runtime is unchanged                                                                                                 |
| Care owns both consents                                   | Separate versioned voice decision/disclosure/current authorization proposal; existing text consent constants and runtime unchanged                                                                         |
| No voice implementation hidden in this PR                 | No service source, migration, active OpenAPI, frontend, worker package, dependency or credential changes                                                                                                   |
| Contract/policy tests and owner handoff                   | [Executable tests](../contracts/proposals/companion-voice-contract.test.mjs), required CI hook and [operator/handoff runbook](ai-voice-demo-runbook.md)                                                    |

The new service-only current authorization reads are necessary prerequisites:
existing Care/Consultation reads require the end-user bearer, which the voice
worker must never receive or persist. Journal resolves the admitted owner from
its session and calls narrowly credentialed owner APIs. These proposed reads do
not imply they already exist on dev.

## Verification

Run from the repository root. Locally, NODE_PATH points only to the existing
locked Journal dependency installation; no credential/environment file is read.
CI installs Journal dependencies with npm ci and needs no NODE_PATH override.

| Check                                                                                                                               | Result                                                                                                                                        |
| ----------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| node --test --test-reporter=spec contracts/proposals/companion-voice-contract.test.mjs                                              | PASS: 55 tests; OpenAPI, closed schemas, all positive/negative fixtures, quotas, dual consent, lifecycle, security and pending-runtime labels |
| Node syntax check for the new test                                                                                                  | PASS                                                                                                                                          |
| Scoped Prettier for new JSON/Markdown/test files                                                                                    | PASS                                                                                                                                          |
| powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-repository.ps1 -BaseSha 11f9b6b4f40bf9df001a39bea98694bcd3400ca3 | PASS before the final handoff-document checkpoint; repeat final result recorded in the PR                                                     |
| Full internal documentation links and final scoped diff                                                                             | Final verification recorded in the PR before publishing                                                                                       |
| Cloud model access, free quota, deployment, microphone, Bedrock calls and real speech                                               | Not run; explicitly gated downstream, not replaced with fakes or claimed as provider evidence                                                 |

The schema tests prove accepted target consistency and rejection of invalid
wire shapes. They do not execute Mongo concurrency, real entitlement/consent
delivery, room teardown, STT/TTS, latency or provider safety. Those acceptance
obligations are explicitly tagged pending in fixtures and owned by MB-756–764.

## Commit slices and rollout

The first checkpoint freezes the mutually dependent ADR/policy/contracts with
their tests and CI hook. The second publishes authority/navigation links,
operator readiness and evidence. Runtime implementation follows only after
contract review, starting with Care/Consultation prerequisites and MB-756; no
API is promoted to implemented merely by this publication.

The full backend quality-gate runs on the pushed PR head. Its exact SHA, run
link and result belong to the PR checks and final handoff; this document records
the pre-publication local checkpoint without fabricating a future CI result.
