# MB-512 AI Companion conversation evidence

## Delivered scope

MB-512 and subtasks MB-527 through MB-530 deliver the versioned AI Companion
conversation boundary defined by ADR 0021:

- encrypted owner-scoped conversation create/list/read/delete;
- synchronous bounded assistant replies with deterministic loading/failure
  behavior;
- Consultation-authoritative FREE/PLUS/PREMIUM routing and quota decisions;
- server-side local-day answer reservations, rate guard, token guard, hidden
  Premium fair-use ceiling, and exact idempotent replay;
- current-consent checks before context assembly and provider execution;
- owner-verified, minimized Journal, longitudinal reassessment, and current
  SupportPlan context; reminder context fails closed until its owner contract
  exists;
- no AI authority over assessment scoring, diagnosis, safety, eligibility,
  SupportPlan state, reminders, or third-party contact;
- frontend history, context selection, quota/reset copy, consent/quota/provider
  failures, hard deletion, and deterministic help-now entry.

## Verification matrix

| Requirement | Automated evidence |
| --- | --- |
| FREE five/day, PLUS configuration, Premium hidden count/fair use | `companion-chat.test.ts`; real-Mongo HTTP integration |
| Local-day reset and stable reset metadata | unit and real-Mongo HTTP integration |
| Duplicate/reused keys and concurrent quota race | unit and real-Mongo HTTP integration |
| Provider failure does not consume answer quota | unit and real-Mongo HTTP integration |
| Rate and token controls | owner unit tests |
| Consent withdrawal and owner isolation | owner unit and real-Mongo HTTP integration |
| Encrypted persistence, no raw context/CoT | unit and real-Mongo persistence assertions |
| Strict same-origin BFF response parsing | frontend validation and Route Handler tests |
| History/send/loading/quota/delete/help-now UI | component tests and Playwright fixture |
| Contract/migration alignment | Journal OpenAPI and migration static validators |

## Verification results

The final PR description records exact commands and CI links. Local evidence is
classified as follows:

- `npm test`: 75/75 unit and HTTP tests passed;
- `npm run test:integration`: 6/6 real MongoDB replica-set integration suites
  passed, including AI Companion concurrency, reset, replay, encryption,
  isolation, failure, consent, Premium metadata, and deletion scenarios;
- `npm run format:check`, `npm run lint`, `npm run typecheck`,
  `npm run contract:check`, `npm run migration:check`, and `npm run build`:
  passed;

- provider contract/unit: synthetic deterministic data;
- Mongo integration: real MongoDB replica-set semantics with synthetic data;
- browser fixture: same-origin mocked provider boundaries with synthetic data;
- live cross-stack: not claimed unless separately run against deployed
  Identity, Care, Consultation, Journal/AI, MongoDB, and frontend services;
- paid Gemini/OpenAI calls: not run and not required; external provider API
  keys remain deployment-owned secrets.

## Privacy and safety review

The persisted conversation contains encrypted user/assistant text, bounded
route provenance, and context-kind labels only. It does not contain the
assembled context prompt, raw Journal plaintext outside encrypted messages,
Care bearer token, provider key, raw provider response, or hidden reasoning.
Request logging continues to redact authorization/cookie headers and does not
log request bodies. AI failure never changes Care safety or SupportPlan state.
