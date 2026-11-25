# MB-370 optional AI ConsultationBrief draft evidence

## Implemented boundary

- Care accepts an owner request only for an exact saved `DRAFT` version and a current `AI_PROCESSING` decision.
- The provider request contains the saved `currentSituation`, one to five saved `userGoals`, and two minimized PHQ-9/GAD-7 level records with version provenance.
- Raw Journal content, screening answers, chat, diagnoses, private notes, specialist data, and appointment mutations are absent.
- Journal/AI rechecks the user bearer, current consent, entitlement, approved route, and strict output schema.
- Care persists the source set, consent, entitlement, routing, provider, model, prompt, and schema versions. Changed or deleted sources cannot produce an applicable result.
- The browser applies a successful result only to editable fields when appointment, brief, brief version, and evaluation all match. Existing save and approval actions remain explicit.

## Failure behavior

Consent rejection, authorization rejection, provider failure, malformed output,
source change, and source deletion produce terminal job states without generated
content. Transport/provider failure has at most two attempts. The manual draft
remains editable throughout.

## Verification

- Journal/AI tests cover success provenance, missing consent, wrong actor,
  invalid/minimized sources, entitlement/provider failures, malformed output,
  and prompt restrictions.
- Care integration tests cover owner access, exact outbound sources, persisted
  provenance, wrong-owner denial, and deletion scrubbing.
- Frontend validation and component tests cover strict response parsing,
  editable prefill, no automatic save/approval, and manual fallback.
