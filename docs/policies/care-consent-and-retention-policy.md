# Care consent and assessment retention policy

## Policy metadata

| Field | Value |
| --- | --- |
| Policy ID | `MB-PRIVACY-CARE-001` |
| Policy version | `1.0-draft.1` |
| Status | `DRAFT — PRODUCT, SECURITY, AND LEGAL REVIEW REQUIRED` |
| Effective date | Pending approval |
| Owning service | Care Service |
| Applies to | Anonymous and registered Care assessment flows in Vietnam |

## Separate consent and disclosure paths

MentalBridge must not combine these purposes into one broad toggle:

| Purpose | Required treatment |
| --- | --- |
| Deterministic assessment processing | Dedicated disclosure and any consent required by the approved legal/product basis; no AI processing is implied |
| AI processing | Separate versioned `AI_PROCESSING` decision before private journal content is sent to an external AI path |
| Specialist sharing | Separate revocable grant scoped to subject, specialist, data type, purpose, time range, and selected entries where applicable |
| Research use | Separate `RESEARCH_DATA` decision; production data is excluded by default |
| Marketing notification | Separate optional `MARKETING_NOTIFICATION` decision |

The current Care consent table supports privacy, AI, research, and marketing decisions. Specialist sharing remains a separate future scoped-grant aggregate, not another broad platform consent. Whether deterministic anonymous/registered assessment requires an explicit consent event or a versioned disclosure acknowledgement remains a legal/product blocker.

## Anonymous assessment

Required behavior:

- token is high entropy, returned once, and stored only as a hash;
- session and result are ephemeral;
- no longitudinal history;
- no specialist access;
- no AI personalization requiring a persistent profile;
- no silent attachment to a later registered account;
- expiry and cleanup are enforced by Care and cannot depend on the client clock.

Proposed product/security value for review:

```text
anonymous inactivity TTL = 30 minutes
```

This is not a clinical requirement and is not approved configuration. Review must define whether activity extends the expiry, the maximum absolute lifetime, cleanup cadence, deletion evidence, and behavior for an in-flight idempotent retry at expiry.

## Registered assessment retention and deletion

The following remain unresolved and must be explicit before runtime implementation is called complete:

- maximum retention duration or user-controlled history duration;
- immediate versus grace-period deletion behavior;
- whether a voided result has a distinct audit retention period;
- treatment of minimized outbox/audit evidence after content deletion;
- backup expiry and restore behavior;
- legal basis and version owner;
- export format and deadline.

No engineering default may convert indefinite retention into policy. An approved retention version governs each stored deadline and historical evidence is not rewritten when a later version changes.

## Approval blockers

- [ ] Assessment-processing disclosure/consent basis approved.
- [ ] Exact privacy and AI-processing text/version ownership approved.
- [ ] Anonymous inactivity TTL, maximum lifetime, cleanup, and retry-at-expiry behavior approved.
- [ ] Registered retention, deletion, audit minimization, and backup expiry approved.
- [ ] Specialist grant policy approved before specialist reads are implemented.
- [ ] Product owner, security reviewer, and legal/privacy reviewer approvals recorded.
