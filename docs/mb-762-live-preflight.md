# MB-762 real-provider preflight

Authority: MB-762 comments 10243–10251, 2026-10-29, and the owner's direct
instruction to run Local Real E2E before staging. Comment 10251 supersedes the
Hosted Agent/HTTPS prerequisite for this first local pilot. This work does not create
another mock-only delivery milestone. Tests verify production changes without
making provider calls before approval.

## Approved pilot

- Total USD 1.00 metered usage maximum: USD 0.50 existing LiveKit Build Inference
  credit and USD 0.50 existing eligible AWS Bedrock credit. Each is additionally
  bounded by its verified available balance. No paid upgrade or cash billing.
- Two web calls, at most 90 seconds each; 180 seconds combined. Four Claude
  turns/call, eight total; 180 STT seconds, 2,400 TTS characters, 16,000 input
  tokens and 2,400 output tokens combined. No retry after unknown outcome.
- Journal/AI owns `global.anthropic.claude-sonnet-4-6` in `ap-southeast-1`.
  No Opus, Gemini fallback or inference in the Agent worker.
- Cartesia `cartesia/sonic-3-2026-01-12`, language `vi`, Xia
  `b8cd71e3-bc14-4538-a530-d6314731c036`. This supersedes the earlier Lien choice.
- PO volunteered to read harmless scripted speech. Both in-product Care
  consents still apply. Reader permission does not establish two distinct owners.
- Keep live admission disabled until preflight is complete and the owner
  explicitly confirms `LIVE_APPROVED`.

## Ordered implementation slices

1. Make Voice routing independently pin the approved Sonnet profile/region
   without modifying AI Text. Include typed configuration, environment example,
   provider boundary tests and this decision record. Build, typecheck and run
   the affected Voice/configuration tests before the checkpoint commit.
2. Implement the pilot's runtime ceilings and inspect privacy/export paths.
   Keep product entitlements/quotas unchanged; pilot controls can only reduce
   admission and provider work. Verify any durable counters against disposable
   MongoDB and update owner migrations/model docs together if required.
3. Verify real account credits, eligible Bedrock billing, monthly LiveKit usage
   and owner-secret wiring. Start Web, Identity, Care, Consultation and Journal
   with isolated local databases; prepare the local named Agent using the
   verified Journal localhost port. Both Voice flags remain OFF before final
   LIVE_APPROVED. Hosted Agent and staging are a separate later validation;
   no EC2 startup, public HTTPS or cloud deployment is required for local proof.

## Actual preflight observations

- Read all seven Jira comments, including the funding and AWS evidence updates.
  AWS Free-plan balance USD 131.02 and expiry 2027-03-08 are owner-supplied
  evidence in comment 10248. Specific Bedrock Applicable Products/credit offset
  remains a separate check, not inferred from that balance or a runtime key.
- Existing LiveKit project credentials passed authenticated read-only ListRooms.
  Official LiveKit CLI 2.19.2, SHA-256 checked against the official release,
  can read Hosted Agents; none exist yet. No room or dispatch was created.
- Browser inventory is empty and in-app browser creation returns
  `Browser is not available: iab`. No dashboard credit/privacy reading is claimed.
- Local Journal configuration still selects fake/Gemini Text. The new scoped
  Voice route uses the existing bearer-authenticated Bedrock Converse adapter,
  with its own pinned region and approval. Configuring the route cannot enable
  admission, and the benchmark model variable is never used to select it.
- No AWS IAM/Billing credential or Agent secret bundle was found in the inspected
  environment. No secret is copied to evidence. Local service tokens will be
  injected outside the repository. The existing staging deployment is powered
  off; it is not a local-pilot prerequisite and will not be started.

Status is **PREFLIGHT_IN_PROGRESS**, not READY_FOR_LIVE_APPROVAL. No inference,
real microphone, paid use, deployment or LIVE_APPROVED action is claimed yet.
