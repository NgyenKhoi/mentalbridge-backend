# MB-647 SupportPlan Companion contract evidence

## Scope

MB-647 freezes a reviewable product, safety and interaction contract before
runtime or client implementation. It adds no endpoint, provider call,
persistence, production UI, database migration or executable prompt.

The canonical artifacts are:

- [ADR 0040](adr/0040-support-plan-companion-contract.md);
- [SupportPlan Companion policy v1](policies/support-plan-companion-policy-v1.md);
- [`support-plan-companion-context-v1`](../contracts/proposals/support-plan-companion-context-v1.schema.json); and
- [`support-plan-companion-intent-v1`](../contracts/proposals/support-plan-companion-intent-v1.schema.json).

## Acceptance mapping

| Jira acceptance area | Frozen evidence |
| --- | --- |
| Persona and role | Policy **Role and persona** defines calm, concise, optional, non-clinical assistance and explicitly excludes therapist/clinician/safety/plan-owner roles. |
| Intents | Intent schema closes the set to explain action/focus, suggest supplied next step, encourage from saved facts, summarize factual progress, explain admitted review options and navigate. |
| Prohibited behavior | ADR and policy prohibit diagnosis, prescription, autonomous mutation, inferred completion, recovery/adherence scores, guilt and ungrounded outcome claims. |
| Triggers and surfaces | Policy trigger matrix covers Today, Plan, Progress and Adjust and requires an explicit user action for every v1 model call. |
| Placement | Policy defines anchored bubble, inline hint, tray, expanded panel and mobile sheet with focus restoration and source-control visibility. |
| Character states | Policy freezes `idle`, `greeting`, `thinking`, `encouraging`, `celebrating-light`, `calm-empathetic` and `waiting`, each with a static text equivalent. |
| Frequency and interruption | Policy bounds deterministic prompts, dismissal/collapse, duplicate pending requests and do-not-interrupt zones. |
| Fallback | Every intent must have deterministic template fallback; silence preserves normal SupportPlan use when safe copy cannot be built. |
| Responsive and reduced motion | Policy requires text equivalence, stable focus, 44 px controls, desktop/375 px/768 px behavior and static reduced-motion states. |
| Context minimization | Context schema is closed and contains no identity, raw Journal/chat/emotion/screening/consultation data, arbitrary provider payload or hidden reasoning. |
| Cross-client handoff | Policy versioning and the closed schemas give MB-648 through MB-654 one language-neutral contract without making it a callable API. |

## Review decisions required before acceptance

- Product confirms the persona, seven intents, surface matrix, placements,
  frequency and fallback behavior.
- Safety/privacy confirms prohibited claims, authoritative-owner boundaries,
  model context exclusions, ephemeral contextual requests and telemetry fields.
- Accessibility confirms focus restoration, live-region restraint, text
  equivalence, touch targets and reduced-motion behavior.

Approval and merge of this PR change the ADR/policy status from proposed to
accepted. Runtime work remains separately gated by MB-649 through MB-654.

## Contract acceptance examples

Valid `EXPLAIN_ACTION` envelope:

```json
{
  "schemaVersion": "support-plan-companion-intent-v1",
  "policyVersion": "support-plan-companion-policy-v1",
  "requestSource": "USER_INITIATED",
  "surface": "TODAY",
  "intent": "EXPLAIN_ACTION",
  "context": {
    "schemaVersion": "support-plan-companion-context-v1",
    "locale": "vi-VN",
    "contextKind": "ACTION",
    "action": {
      "actionType": "RESOURCE",
      "title": "Đọc hướng dẫn thư giãn ngắn",
      "availability": "AVAILABLE",
      "dueState": "DUE_TODAY",
      "navigation": {
        "destination": "RESOURCE",
        "label": "Mở hướng dẫn"
      }
    }
  }
}
```

Valid partial factual progress envelope:

```json
{
  "schemaVersion": "support-plan-companion-intent-v1",
  "policyVersion": "support-plan-companion-policy-v1",
  "requestSource": "USER_INITIATED",
  "surface": "PROGRESS",
  "intent": "SUMMARIZE_PROGRESS",
  "context": {
    "schemaVersion": "support-plan-companion-context-v1",
    "locale": "vi-VN",
    "contextKind": "PROGRESS",
    "progress": {
      "period": "SEVEN_DAYS",
      "completedCount": 3,
      "availableCount": 5,
      "evidenceAsOf": "2026-10-14T08:30:00Z",
      "partial": true,
      "unavailableEvidence": ["JOURNAL_ACTION_EVIDENCE"]
    }
  }
}
```

The contract rejects all of these cases before provider execution:

- `EXPLAIN_ACTION` paired with `PROGRESS` context;
- an unknown intent, context kind, action type or destination;
- automatic/model-initiated request source;
- any extra field such as `accountId`, `journalText`, `emotionIntensity`,
  `screeningScore`, `diagnosis`, `recoveryScore` or `providerPayload`;
- missing version, locale, required factual field, or out-of-bound string/count;
- an acknowledgement without one of the closed saved-fact reasons.

## Verification record

Local verification on 2026-10-14:

| Check | Result |
| --- | --- |
| PowerShell `ConvertFrom-Json` for both schemas | Passed |
| `ajv-cli@5 compile --spec=draft2020` for context and intent schemas | Passed in strict mode without schema warnings |
| Ajv valid `EXPLAIN_ACTION` fixture | Accepted |
| Ajv mismatched intent/context fixture | Rejected as expected |
| Ajv disallowed surface/intent fixture | Rejected as expected |
| Ajv extra sensitive `accountId` fixture | Rejected as expected |
| Relative links in the four new canonical/evidence documents | Passed |
| Required intent/state/motion vocabulary audit | Passed |
| `scripts/verify-repository.ps1 -BaseSha origin/dev` | Passed: repository and paired-change policies |
| `git diff --check` | Passed |

GitHub quality-gate evidence is added to the PR after the exact head is pushed.
