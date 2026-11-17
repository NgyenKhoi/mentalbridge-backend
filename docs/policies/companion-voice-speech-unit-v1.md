# Companion Voice independently publishable speech units v1

Status: **PROPOSED FOR CODE OWNER REVIEW; default OFF, not live approval.**
PO authorized development on16-November2026 in MB-854. This prospective policy
does not amend the existing all-or-nothing v1 publication path when disabled.

## Explicit safety difference

The old path requires complete model END_TURN, full structured response and
whole-message safety before any speech. This mode defines a complete speech
unit as the publication boundary. A later abnormal model stop, invalid unit or
transport failure cancels future output, but cannot recall already heard audio.
That difference requires owner source/policy approval before activation. Passing
fixtures does not prove clinical equivalence or real listening acceptance.

The current server safety validator is retained for each complete unit and the
cumulative prefix. All existing knowledge/guidance, consent, context, provider
pins and input/output token restrictions remain. Model-produced NONE fields
are necessary schema constraints, not a safety authority. New instructions
require independently complete sentences with no deferred safety qualifier.
No second moderation model, new clinical detector or blanket safe-prefix rule
is introduced. Cross-unit semantic effects beyond existing validators remain
a review risk; if that difference is unacceptable, keep this mode OFF.

## Publication contract

- Approved Bedrock ConverseStream and bound measurement profile are required.
  Both Journal and Agent opt in. An incompatible worker is rejected at claim;
  old single-response turns are not silently accepted in the new mode.
- The model emits one JSON array of at most two complete structured objects.
  Each object retains the existing closed authority/safety fields, Vietnamese
  language, complete schema and optional neutral/comfort expression.
- Each message must pass the existing safety validator, complete sentence
  punctuation, markup/link denial and combined500-character ceiling. Incomplete
  JSON, malformed UTF8, invalid schema, unsafe or oversized text never publishes.
- Each unit gets a fresh publication authorization and a state/lease recheck.
  Since MB-867 (owner decision 2026-11-17) its LiveKit media inspection may
  reuse the turn's successful inspection for up to 5 s; failures are never
  reused and owner consent/entitlement stay fresh on every unit.
  The first reserves **500 campaign TTS characters once** before publication,
  conservatively covering the entire turn. Failed/cancelled/short responses do
  not refund this reservation. No campaign schema or product quota is changed.
- All units share one registered turn/reply identity. Per-stream unit sequence
  is1..2, not a new provider turn. Existing PLAYBACK_STARTED/INTERRUPTED controls
  keep delivery/quota semantics. After complete playback Agent sends a bound ACK;
  only then is the next unit freshly authorized. No stale queued lease is reused.
- ACK/cancel commands require current bound attempt/lease and the exact turn,
  reply and sequence on the same Journal process. Multi-process routing without
  session affinity is not supported; it fails closed rather than replaying.
- HTTP is one bounded incremental application/json array, no-store and
  X-Accel-Buffering:no. Existing scoped worker token protects all routes.
  No public Web API or disclosure data-sharing change is introduced.

## Cancellation, limits and privacy

Provider wire consumption runs independently of publication/TTS. No network
request is made inside a database transaction. One shared CountTokens/request
cap applies to the whole response, not each object. No retry/fallback is added.
The existing session, campaign, entitlement, consent and lease checks remain.

Barge-in interrupts local audio immediately and cancels the exact provider turn.
The next user turn can use the same live session only after cancellation cleanup;
spent turn/TTS reservations stay spent. Unknown cancellation, consent/authority
failure, timeout, malformed stream or provider failure closes the session.
No reconnect retry, new admission or replacement window is created.

Partial model text, authorized speech and current-call context stay only in RAM.
No transcript/audio/content logging/export/storage is added. Existing metadata
retention applies. Diagnostics contain opaque IDs, phase/sequence/count/timing
and closed failure enums only. Unknown usage is not reported as zero spend.

## Measurement and rollout

The JSON/OpenAPI contract is
[journal-ai-voice-speech-units-v1.json](../../contracts/openapi/journal-ai-voice-speech-units-v1.json).
The sole consumer is ai-voice-agent; no frontend contract sync is needed for
these internal routes. No active main Journal schema or memberPR299 file is
modified by this proposal. Normal Voice/Text stay compatible with both flagsOFF.

Exact-head source review/CI, controlled promotion and OFF-first deployment are
required. Then verify one Journal replica/same-process ACK routing, matching
Agent flags/policy digest, cumulative hard caps, current owner consent/quota,
credit/no-overage/privacy and a separately confirmed window. No source approval
implicitly starts a window. USD0.50/2sessions/concurrency1/12turns/480s/4000chars
remain total limits. A500-character reservation per generated turn can exhaust
the2000-character session allocation after four responses even if actual text
is shorter; do not hide that conservative limit or refund to force more samples.

True completion requires real first audible audio before the model messageStop,
independent browser end-of-speech/heard timing, matched baseline/comparison and
teardown evidence. Synthetic delayed-stream fixtures prove ordering only, not
provider speed, p50/p95, clinical safety or the30% target. Until measured and
approved, report PARTIAL and keep STREAMING DONE unclaimed.
