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
  speechUsage,
} from "./livekit-media.js";
import { DispatchAdmission, run } from "./main.js";
import { agentName } from "./contract.js";
import { Room as RtcRoom, TrackKind, TrackSource } from "@livekit/rtc-node";
import { initializeLogger } from "@livekit/agents";
import { microphoneSubscriptions } from "./livekit-media.js";
import type { Transcript } from "./voice-worker.js";

void test("speech metrics retain only numeric usage even when cancelled, never provider identifiers or content", () => {
  const raw = {
    type: "tts_metrics",
    audioDurationMs: 1200,
    charactersCount: 40,
    cancelled: true,
    text: "PRIVATE",
    requestId: "PRIVATE",
    metadata: { private: "PRIVATE" },
  };
  const filtered = speechUsage(raw);
  assert.deepEqual(filtered, {
    phase: "speech_usage",
    component: "TTS",
    audioDurationMs: 1200,
    charactersCount: 40,
    cancelled: true,
  });
  assert.deepEqual(speechUsage({ ...raw, type: "stt_metrics" }), {
    phase: "speech_usage",
    component: "STT",
    audioDurationMs: 1200,
  });
  assert.equal(speechUsage({ ...raw, audioDurationMs: Number.NaN }), null);
  assert.equal(speechUsage({ ...raw, charactersCount: -1 }), null);
  assert.equal(speechUsage({ type: "llm_metrics" }), null);
  assert.equal(JSON.stringify(filtered).includes("PRIVATE"), false);
});

void test("pinned SDK creates an input while detached, then subscribes only the authorized microphone", async () => {
  initializeLogger({ pretty: false, level: "silent" });
  const room = new RtcRoom();
  const session = new voice.AgentSession(sessionOptions);
  session.input.setAudioEnabled(false);
  const io = new voice.RoomIO({
    room,
    agentSession: session,
    inputOptions: startOptions.inputOptions,
    outputOptions: { ...startOptions.outputOptions, audioEnabled: false },
  });
  io.start();
  try {
    const input = session.input.audio;
    assert.ok(input, "audioEnabled:false would omit RoomIO input permanently");
    assert.equal(session.input.audioEnabled, false);
    const attached: boolean[] = [];
    input.setAttached = (enabled) => {
      attached.push(enabled);
    };
    session.input.setAudioEnabled(true);
    session.input.setAudioEnabled(false);
    assert.deepEqual(attached, [true, false]);
    const subscriptions: boolean[][] = [[], [], []];
    const publications = [
      { kind: TrackKind.KIND_AUDIO, source: TrackSource.SOURCE_MICROPHONE },
      { kind: TrackKind.KIND_VIDEO, source: TrackSource.SOURCE_CAMERA },
      {
        kind: TrackKind.KIND_AUDIO,
        source: TrackSource.SOURCE_SCREENSHARE_AUDIO,
      },
    ].map((value, index) => ({
      ...value,
      setSubscribed: (enabled: boolean) => {
        subscriptions[index]?.push(enabled);
      },
    }));
    microphoneSubscriptions(publications, false);
    microphoneSubscriptions(publications, true);
    microphoneSubscriptions(publications, false);
    assert.deepEqual(subscriptions, [
      [false, true, false],
      [false, false, false],
      [false, false, false],
    ]);
  } finally {
    await io.close();
    await room.disconnect();
  }
});

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
