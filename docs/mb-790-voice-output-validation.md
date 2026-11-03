# MB-790 Voice response validation follow-up

The extended local pilot reached STT, authorized Journal turns and Sonnet.
Two Voice responses validated and reached TTS. The next response arrived after
3,376 ms, reported 1,620 input and exactly 300 output tokens, then failed before
response validation. The session failed with PROVIDER_FAILURE, settled its quota
and completed teardown: room/Egress/open-session counts zero, Voice/Agent OFF.
It did not reach the duration, turn-count, TTS-character or credit cap.

Exactly 300 output tokens is consistent with the complete JSON response being
truncated at the configured limit. Historical stopReason and response content
were not retained, so truncation is strongly suspected, not proven. The old
INVALID_RESPONSE label also covered malformed JSON, schema and safety failures.
No transcript or provider response is requested from the person testing.

## Correction

- An explicit pilot may set maxOutputTokens from 300 through 600. Omission
  preserves the existing 300-token ceiling; no past approval or active window
  is expanded. A future launcher/budget fence must authorize the same limit.
- The allowance covers the entire JSON object, including fixed classification
  fields. The 500-character spoken-message limit and safety schema stay intact.
- Voice reports the closed stop categories END_TURN, MAX_TOKENS,
  STOP_SEQUENCE, OTHER or MISSING at bedrock_output_validation. Raw provider
  stop strings, response bodies, transcripts and error messages are excluded.
- Only END_TURN is an accepted Voice completion state. MAX_TOKENS fails as
  OUTPUT_TOKEN_LIMIT; MISSING, STOP_SEQUENCE and OTHER fail as
  UNEXPECTED_STOP_REASON, even if the returned JSON is otherwise valid. No stop
  sequence is configured for this request. Envelope, JSON, schema and safety
  failures have separate closed codes. Observed token usage is accounted before
  validation.
- No retry, repair of incomplete output, provider fallback or unchecked speech
  is introduced. Text output policy, input counting, consent, quota, leases,
  session deadlines and recording/export controls are unchanged.

AWS defines stopReason on the
[Converse response](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Converse.html).
The new metadata records only our fixed categories, not arbitrary provider text.

## Verification boundary

Synthetic provider fixtures exercise complete output, token-limited/truncated
output, abnormal stop strings, malformed envelope/JSON, wrong schema, and unsafe
content. They assert one request, preserved usage, no unsafe validation success
and no content in observations. A separate request-boundary test checks the
legacy default and an explicit 600-token allowance with a valid response above
300 tokens. These checks do not claim a successful new live call.

This patch is based on dev after MB-790 merged. The old pilot worktree and
campaign remain frozen. No new admission, provider probe, grant renewal or
microphone test is authorized or performed by applying this source change.

## Executed checks

Rebased onto dev 83181500 after MB-791 merged, preserving its configured
temperature, friend guidance and link-safety validation. The diff contains only
this follow-up; it does not republish the earlier MB-790/MB-791 feature commits.

- Journal formatting, lint, non-emitting TypeScript, build, contracts and
  migrations: PASS, including the current migration 017 validation.
- Complete Journal unit/HTTP/provider suite: 277/277 PASS.
- Real disposable Mongo replica-set Voice turn integration: 25/25 PASS,
  including output-token failure and missing/stop-sequence completion, retained
  usage/diagnostic and zero reply publication.
- Documentation links/ownership and repository paired-change policy: PASS.
- Independent source review: no confirmed privacy, correctness or compatibility
  defect; the prior live failure remains probable token exhaustion rather than
  a proven historical stopReason.

These results validate the source fix, not a new live campaign. Old caps and
fences remain immutable; a later 600-token pilot needs a matching new manifest
and budget before admission. The old 300-token fence would reject it.

## Completion-state review correction

The initial b302984e guard rejected MAX_TOKENS and OTHER but incorrectly let
MISSING and STOP_SEQUENCE continue. Source review identified this fail-closed
gap despite the earlier green checks. Completion now requires END_TURN before
envelope/JSON/schema/safety validation. MAX_TOKENS keeps its specific code;
every other completion category rejects as UNEXPECTED_STOP_REASON.

Regressions use otherwise valid JSON with missing/stop-sequence completion.
They exercise the actual provider adapter through Journal and real disposable
Mongo fixtures, checking one request, one usage observation, failed settlement,
no validated response, no publication authorization or reply registration, no
retry and no private content in observations. Existing successful Voice fixtures
now explicitly declare end_turn. Text-only fixtures keep their prior behavior.

After this review correction, the full 277-test Journal suite, 25-test Mongo
suite, format/lint/typecheck/build, contracts, migrations, documentation and
repository checks passed again. No provider call or budget approval is implied.
