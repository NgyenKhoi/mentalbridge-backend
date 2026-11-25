# MB-791 — local Voice diagnostic evidence

## Observed boundaries on 3 November 2026

The local manual attempt used backend `4df2e0ff` and Web `1529dc0a`.
The Agent received microphone frames and acknowledged ten heartbeats. Fourteen
STT finals were empty and one nonempty Vietnamese final had confidence
`0.53808594`, below the unchanged `0.8` threshold. There were no Journal turns,
CountTokens, Converse or reply TTS calls. The authoritative session reason was
`IDLE_TIMEOUT`; the subsequent room-disconnect diagnostic was a consequence.
Microphone hardware or provider quality cannot be identified as the cause from
these aggregates. No recording or transcript was retained.

The owner then requested an automated real test before another handoff. A
separate bounded attempt reached Nova-3, a Journal-authorized turn, exact input
counting, Sonnet 4.6, response validation, reply registration and Liên audio
publication. Bedrock reported 775 input and 99 output tokens with `END_TURN`;
the native RTC client received non-silent reply audio. This proves those
boundaries, without claiming human listening or full-call success.

The synthetic sentence produced several valid STT segments. A later segment
attempted turn two after the first reply in a pilot limited to one provider
turn. That cap stopped the call before another CountTokens or Converse, but
`VoiceSessionsService.failTurn` incorrectly stored `PROVIDER_FAILURE` for the
quota stop. The first synthetic client consequently did not finish its full
playout/owner-end verification. This attempt remains consumed; it is not an
audio E2E PASS.

## Correction and invariants

The pilot turn-limit path now requests termination with the already supported
`QUOTA_EXHAUSTED` reason. Other callers retain the `PROVIDER_FAILURE` default.
This changes classification only: the cap is not extended, no provider retry
or fallback is added, the existing terminal-state mapping and settlement are
preserved, and media/context teardown still runs. No API, migration, persisted
shape, consent, provider, confidence or financial policy changes are required.

Real disposable Mongo regressions cover the default cap, a one-turn pilot
after its first generated reply, authority/context teardown, no extra provider
call, and preservation of real provider-failure classification.

## Separate Text runtime correction

The Voice-only local cost fence denied Text requests. A distinct, bounded Text
window restored the real Sonnet route; one synthetic request through the Web
BFF returned HTTP 201 in approximately 2.6 seconds and passed response
validation. The initial local probe expected HTTP 200 and an object-valued
assistant, while the actual contract uses HTTP 201 and a string. Read-only
response verification corrected that probe result without another POST.

The automated harness also corrected stale cached BFF authentication and a
missing same-origin header. The explicit HTTP 403 occurred before upstream
authorization: the local ledger proved zero admissions and provider calls
before the corrected POST. No security guard was disabled. Historical results
and budget reservations remain unchanged; credentials and runtime env files
remain outside Git.

Real listening quality, laptop-microphone recognition and a complete normal
Voice teardown require their own successful observations. Neither CI nor a
partial audio result establishes them.
