import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { randomUUID } from "node:crypto";
import { ReadableStream } from "node:stream/web";
import test from "node:test";
import { asLanguageCode, stt, voice, type JobRequest } from "@livekit/agents";
import { Job, Room } from "@livekit/protocol";
import {
  approvedPlayback,
  transcriptFilter,
  sessionOptions,
  startOptions,
} from "./livekit-media.js";
import { DispatchAdmission, run } from "./main.js";
import { agentName } from "./contract.js";
import type { Transcript } from "./voice-worker.js";

void test("SDK stream sends final/interim only to coordinator and removes transcript from SDK history", async () => {
  const received: Transcript[] = [];
  const events: (stt.SpeechEvent | string)[] = [
    { type: stt.SpeechEventType.START_OF_SPEECH },
    {
      type: stt.SpeechEventType.INTERIM_TRANSCRIPT,
      alternatives: [
        {
          text: "Hôm nay",
          language: asLanguageCode("vi"),
          confidence: 0.9,
          startTime: 0,
          endTime: 1,
        },
      ],
    },
    {
      type: stt.SpeechEventType.FINAL_TRANSCRIPT,
      alternatives: [
        {
          text: "Hôm nay tôi hơi lo",
          language: asLanguageCode("vi"),
          confidence: 0.95,
          startTime: 0,
          endTime: 2,
        },
      ],
    },
    "provider transcript must not enter SDK history",
    { type: stt.SpeechEventType.END_OF_SPEECH },
  ];
  const stream = new ReadableStream<stt.SpeechEvent | string>({
    start(controller) {
      for (const event of events) controller.enqueue(event);
      controller.close();
    },
  }).pipeThrough(
    transcriptFilter((value) => {
      received.push(value);
    }),
  );
  const forwarded = [];
  for await (const event of stream) forwarded.push(event);
  assert.deepEqual(forwarded, [events[0], events[4]]);
  assert.equal(received.length, 2);
  assert.equal(received[0]?.final, false);
  assert.equal(received[1]?.final, true);
  assert.equal(received[1].durationMs, 2000);
  assert.match(received[1].segmentId, /^[0-9a-f]{64}$/u);
  assert.equal(startOptions.record, false);
  assert.equal(startOptions.inputOptions.textEnabled, false);
  assert.equal(startOptions.outputOptions.transcriptionEnabled, false);
  assert.equal(sessionOptions.turnHandling.preemptiveGeneration.enabled, false);
});

void test("playback proof requires actual SDK first-frame event, deduplicates it and removes listeners", async () => {
  const output = new EventEmitter();
  let frames = 0;
  let finish: () => void = () => undefined;
  const playout = new Promise<void>((resolve) => {
    finish = resolve;
  });
  const playback = approvedPlayback(
    output,
    () => ({ waitForPlayout: () => playout, exception: () => null }),
    () => {
      frames++;
      return Promise.resolve();
    },
    new AbortController().signal,
    () => undefined,
    () => assert.fail("unexpected failure"),
  );
  assert.equal(frames, 0);
  output.emit(voice.AudioOutput.EVENT_PLAYBACK_STARTED);
  output.emit(voice.AudioOutput.EVENT_PLAYBACK_STARTED);
  finish();
  await playback;
  assert.equal(frames, 1);
  assert.equal(
    output.listenerCount(voice.AudioOutput.EVENT_PLAYBACK_STARTED),
    0,
  );
  await assert.rejects(
    approvedPlayback(
      output,
      () => ({
        waitForPlayout: () => Promise.resolve(),
        exception: () => null,
      }),
      () => Promise.resolve(),
      new AbortController().signal,
      () => undefined,
      () => undefined,
    ),
    /PROVIDER_FAILED/u,
  );
});

void test("aborted TTS cuts playback immediately; denied publication fails closed without retry", async () => {
  const output = new EventEmitter();
  const abort = new AbortController();
  let cuts = 0;
  let failures = 0;
  let release: () => void = () => undefined;
  const wait = new Promise<void>((resolve) => {
    release = resolve;
  });
  const play = approvedPlayback(
    output,
    () => ({ waitForPlayout: () => wait, exception: () => null }),
    () => Promise.reject(new Error("AUTHORITY_EXPIRED")),
    abort.signal,
    () => {
      cuts++;
      release();
    },
    () => {
      failures++;
    },
  );
  output.emit(voice.AudioOutput.EVENT_PLAYBACK_STARTED);
  await assert.rejects(play, /AUTHORITY_EXPIRED/u);
  assert.equal(cuts, 1);
  assert.equal(failures, 1);
  const interrupted = approvedPlayback(
    output,
    () => ({
      waitForPlayout: () =>
        new Promise<void>((resolve) => {
          release = resolve;
        }),
      exception: () => null,
    }),
    () => assert.fail("no audio emitted"),
    abort.signal,
    () => {
      cuts++;
      release();
    },
    () => undefined,
  );
  abort.abort();
  await interrupted;
  assert.equal(cuts, 2);
});

void test("named dispatch admits only one bounded AI attempt, never a consultation/recorded room or duplicate", async () => {
  let now = 0;
  const admissions = new DispatchAdmission(() => now);
  const dispatch = {
    voiceSessionId: randomUUID(),
    attemptId: randomUUID(),
    dispatchId: randomUUID(),
  };
  let accepted = 0;
  let rejected = 0;
  const room = new Room({
    name: `ai-voice-${randomUUID()}`,
    metadata: dispatch.dispatchId,
  });
  const request: Pick<
    JobRequest,
    "agentName" | "job" | "room" | "accept" | "reject"
  > = {
    agentName,
    room,
    job: new Job({ metadata: JSON.stringify(dispatch), room }),
    accept: (_name, identity, metadata, attributes) => {
      assert.equal(identity, `va-${dispatch.attemptId}`);
      assert.equal(metadata, "");
      assert.deepEqual(attributes, {});
      accepted++;
      return Promise.resolve();
    },
    reject: () => {
      rejected++;
      return Promise.resolve();
    },
  };
  await admissions.accept(request);
  await admissions.accept(request);
  assert.equal(accepted, 1);
  assert.equal(rejected, 1);
  now = 600001;
  const roomData = { name: room.name, metadata: room.metadata };
  for (const invalid of [
    { ...request, agentName: "" },
    {
      ...request,
      room: new Room({ ...roomData, name: "consultation-private" }),
    },
    { ...request, room: new Room({ ...roomData, activeRecording: true }) },
    { ...request, room: new Room({ ...roomData, metadata: randomUUID() }) },
    { ...request, room: new Room({ ...roomData, numParticipants: 2 }) },
    {
      ...request,
      job: new Job({
        metadata: JSON.stringify({ ...dispatch, userJwt: "forbidden" }),
      }),
    },
  ])
    await admissions.accept(invalid);
  assert.equal(accepted, 1);
  assert.equal(rejected, 7);
  await assert.rejects(run({}), /VOICE_DISABLED/u);
});
