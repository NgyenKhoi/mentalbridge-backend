import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { randomUUID } from "node:crypto";
import { ReadableStream } from "node:stream/web";
import test from "node:test";
import {
  asLanguageCode,
  inference,
  stt,
  voice,
  type JobRequest,
  type JobContext,
} from "@livekit/agents";
import { Job, Room } from "@livekit/protocol";
import {
  approvedPlayback,
  transcriptFilter,
  sessionOptions,
  startOptions,
  speechUsage,
  recognitionLanguage,
  sttModelOptions,
  LiveKitMedia,
  inputAudioLevel,
  sentenceChunks,
  sentencePauseMs,
  silence,
  speechTts,
  ttsModelOptions,
} from "./livekit-media.js";
import * as elevenlabs from "@livekit/agents-plugin-elevenlabs";
import { loadConfiguration } from "./configuration.js";
import { DispatchAdmission, run } from "./main.js";
import { agentName, policyVersion, type Dispatch } from "./contract.js";
import {
  AudioFrame,
  Room as RtcRoom,
  TrackKind,
  TrackSource,
} from "@livekit/rtc-node";
import { initializeLogger } from "@livekit/agents";
import { microphoneSubscriptions } from "./livekit-media.js";
import {
  VoiceWorker,
  type MediaCallbacks,
  type MediaPort,
  type Transcript,
} from "./voice-worker.js";
import { mediaFailure, type AgentDiagnostic } from "./diagnostics.js";
import type { JournalClient } from "./journal-client.js";
import {
  sttServerEventSchema,
  sttKnownServerEventSchema,
} from "../node_modules/@livekit/agents/dist/inference/api_protos.js";
import { SpeechStream } from "../node_modules/@livekit/agents/dist/inference/stt.js";

interface FinalWireTranscript {
  type: "final_transcript";
  session_id?: string;
  transcript: string;
  language: string;
  start: number;
  duration: number;
  confidence: number;
  words: {
    word: string;
    start: number;
    end: number;
    confidence: number;
  }[];
}
interface SyntheticSpeechStream {
  queue: { closed: boolean; put(event: stt.SpeechEvent): void };
  opts: { language: string };
  requestId: string;
  startTimeOffset: number;
  speechDuration: number;
  speaking: boolean;
  onEndOfSpeech(): void;
  processStartOfSpeech(): void;
}
const sdkTranscriptMethods = SpeechStream.prototype as unknown as {
  processStartOfSpeech(this: SyntheticSpeechStream): void;
  processTranscript(
    this: SyntheticSpeechStream,
    data: FinalWireTranscript,
    event: stt.SpeechEventType,
  ): void;
};

void test("pinned SDK wire validation, transcript processing and worker preserve Vietnamese finals and confidence rules", async (t) => {
  const cases: {
    name: string;
    wire: Record<string, unknown>;
    wireAccepted: boolean;
    turns: number;
    rejection?: string;
    empty?: boolean;
    discarded?: boolean;
    confidence?: number;
  }[] = [
    { name: "vi", wire: {}, wireAccepted: true, turns: 1 },
    {
      name: "vi-VN",
      wire: { language: "vi-VN" },
      wireAccepted: true,
      turns: 1,
    },
    {
      name: "vi-Latn-VN",
      wire: { language: "vi-Latn-VN" },
      wireAccepted: true,
      turns: 1,
    },
    {
      name: "en-VN",
      wire: { language: "en-VN" },
      wireAccepted: true,
      turns: 0,
      rejection: "LANGUAGE_MISMATCH",
    },
    {
      name: "malformed Vietnamese tag",
      wire: { language: "vi---" },
      wireAccepted: true,
      turns: 0,
      rejection: "LANGUAGE_MISMATCH",
    },
    {
      name: "SDK repairs underscore tag before adapter validation",
      wire: { language: "vi_VN" },
      wireAccepted: true,
      turns: 1,
    },
    {
      name: "confidence below threshold",
      wire: { confidence: 0.79 },
      wireAccepted: true,
      turns: 0,
      rejection: "LOW_CONFIDENCE",
      discarded: true,
    },
    {
      name: "confidence at threshold",
      wire: { confidence: 0.8 },
      wireAccepted: true,
      turns: 1,
      confidence: 0.8,
    },
    {
      name: "omitted confidence uses SDK default",
      wire: { confidence: undefined },
      wireAccepted: true,
      turns: 1,
      confidence: 1,
    },
    {
      name: "null confidence is discarded by SDK wire validation",
      wire: { confidence: null },
      wireAccepted: false,
      turns: 0,
    },
    {
      name: "confidence above one",
      wire: { confidence: 1.01 },
      wireAccepted: true,
      turns: 0,
      rejection: "INVALID_CONFIDENCE",
    },
    {
      name: "negative confidence",
      wire: { confidence: -0.1 },
      wireAccepted: true,
      turns: 0,
      rejection: "INVALID_CONFIDENCE",
    },
    {
      name: "empty final emits usage without a turn",
      wire: { transcript: "" },
      wireAccepted: true,
      turns: 0,
      empty: true,
    },
    {
      name: "whitespace final emits usage without a turn",
      wire: { transcript: "   " },
      wireAccepted: true,
      turns: 0,
      empty: true,
    },
  ];
  for (const row of cases) {
    await t.test(row.name, async () => {
      let now = Date.now();
      const dispatch: Dispatch = {
        voiceSessionId: randomUUID(),
        attemptId: randomUUID(),
        dispatchId: randomUUID(),
      };
      const leaseId = randomUUID();
      const hardStopAt = new Date(now + 90000).toISOString();
      let sequence = 0;
      let turns = 0;
      let spoken = 0;
      let callbacks: MediaCallbacks | undefined;
      const diagnostics: AgentDiagnostic[] = [];
      const journal: Pick<JournalClient, "claim" | "control" | "turn"> = {
        claim: () =>
          Promise.resolve({
            ...dispatch,
            leaseId,
            hardStopAt,
            validUntil: new Date(now + 10000).toISOString(),
            policyVersion,
          }),
        control: () => {
          const validUntil = new Date(now + 10000).toISOString();
          return Promise.resolve({
            action: "CONTINUE",
            control: {
              schemaVersion: 1,
              commandId: randomUUID(),
              voiceSessionId: dispatch.voiceSessionId,
              attemptId: dispatch.attemptId,
              sequence: ++sequence,
              issuedAt: new Date(now).toISOString(),
              expiresAt: validUntil,
              kind: "LEASE_RENEWED",
              payload: { leaseId, validUntil, hardStopAt, admittedSeconds: 90 },
            },
          });
        },
        turn: (_dispatch, turn) => {
          turns++;
          return Promise.resolve({
            voiceSessionId: dispatch.voiceSessionId,
            turnId: turn.turnId,
            replyId: randomUUID(),
            language: "vi-VN",
            spokenText: "Synthetic response.",
            expiresAt: new Date(now + 5000).toISOString(),
            policyVersion,
          });
        },
      };
      const media: MediaPort = {
        state: () => undefined,
        start: (value) => {
          callbacks = value;
          return Promise.resolve();
        },
        input: () => undefined,
        speak: async (_text, started) => {
          spoken++;
          await started();
        },
        interrupt: () => undefined,
        close: () => Promise.resolve(),
      };
      const worker = new VoiceWorker(
        dispatch,
        journal,
        media,
        () => now,
        () => undefined,
        (value) => diagnostics.push(value),
      );
      await worker.start();
      try {
        assert.ok(callbacks);
        const receive = callbacks.transcript;
        const events: stt.SpeechEvent[] = [];
        const stream: SyntheticSpeechStream = {
          queue: { closed: false, put: (event) => events.push(event) },
          opts: { language: "vi" },
          requestId: "synthetic",
          startTimeOffset: 0,
          speechDuration: 1,
          speaking: false,
          onEndOfSpeech: () => undefined,
          processStartOfSpeech() {
            sdkTranscriptMethods.processStartOfSpeech.call(this);
          },
        };
        const raw: unknown = JSON.parse(
          JSON.stringify({
            type: "final_transcript",
            session_id: "synthetic",
            transcript: "PRIVATE_SYNTHETIC_TRANSCRIPT",
            language: "vi",
            confidence: 0.95,
            start: 0,
            duration: 1,
            words: [],
            ...row.wire,
          }),
        );
        const parsed = await sttServerEventSchema.safeParseAsync(raw);
        assert.equal(parsed.success, row.wireAccepted);
        const known = sttKnownServerEventSchema.safeParse(raw);
        assert.equal(known.success, row.wireAccepted);
        if (known.success) {
          const final = known.data as FinalWireTranscript;
          assert.equal(final.type, "final_transcript");
          if (row.confidence !== undefined)
            assert.equal(final.confidence, row.confidence);
          sdkTranscriptMethods.processTranscript.call(
            stream,
            final,
            stt.SpeechEventType.FINAL_TRANSCRIPT,
          );
          assert.deepEqual(
            events.map((event) => event.type),
            [
              stt.SpeechEventType.START_OF_SPEECH,
              stt.SpeechEventType.RECOGNITION_USAGE,
              stt.SpeechEventType.FINAL_TRANSCRIPT,
              stt.SpeechEventType.END_OF_SPEECH,
            ],
          );
        } else assert.deepEqual(events, []);
        const filtered = new ReadableStream<stt.SpeechEvent>({
          start(controller) {
            for (const event of events) controller.enqueue(event);
            controller.close();
          },
        }).pipeThrough(transcriptFilter(receive));
        const forwarded: (stt.SpeechEvent | string)[] = [];
        for await (const event of filtered) forwarded.push(event);
        assert.equal(
          forwarded.some(
            (event) =>
              typeof event !== "string" &&
              event.type === stt.SpeechEventType.FINAL_TRANSCRIPT,
          ),
          false,
        );
        now += 1600;
        await worker.tick();
        await new Promise<void>((resolve) => setImmediate(resolve));
        assert.equal(turns, row.turns);
        assert.equal(spoken, row.turns);
        const rejected = diagnostics.find(
          (value) =>
            value.phase === "stt_final" &&
            ["REJECTED", "DISCARDED"].includes(value.transcriptResult ?? ""),
        );
        assert.equal(rejected?.rejection, row.rejection);
        if (row.discarded) {
          assert.equal(rejected?.transcriptResult, "DISCARDED");
          assert.equal(worker.state, "LISTENING");
        }
        assert.equal(
          diagnostics.some(
            (value) =>
              value.phase === "stt_final" && value.transcriptResult === "EMPTY",
          ),
          row.empty === true,
        );
        assert.equal(
          diagnostics.find((value) => value.phase === "agent_stopped")?.reason,
          row.rejection && !row.discarded ? "PROVIDER_FAILURE" : undefined,
        );
        assert.equal(JSON.stringify(diagnostics).includes("PRIVATE"), false);
      } finally {
        await worker.stop("USER_ENDED", false);
      }
    });
  }
});

void test("Vietnamese recognition tags normalize without accepting a different or malformed language", () => {
  for (const language of ["vi", "vi-VN", "VI-vn", "vi-Latn-VN", "vie"])
    assert.equal(recognitionLanguage(language), "vi");
  for (const language of ["en", "en-VN", "vi_VN", "vi---", "", "private-value"])
    assert.equal(recognitionLanguage(language), language);
});

void test("SDK failure metadata excludes messages, provider bodies, raw language and content", () => {
  const event = {
    message: "PRIVATE_MESSAGE",
    transcript: "PRIVATE_TRANSCRIPT",
    source: "PRIVATE_SOURCE",
    error: {
      type: "stt_error",
      recoverable: false,
      error: {
        statusCode: 429,
        message: "PRIVATE_ERROR",
        body: "PRIVATE_BODY",
        headers: { authorization: "PRIVATE_KEY" },
      },
    },
  };
  const safe = mediaFailure("SDK_ERROR", event);
  assert.deepEqual(safe, {
    source: "SDK_ERROR",
    component: "STT",
    recoverable: false,
    code: "HTTP_4XX",
    statusCode: 429,
  });
  assert.equal(JSON.stringify(safe).includes("PRIVATE"), false);
  assert.deepEqual(
    mediaFailure("SDK_CLOSE", {
      error: { type: "tts_error", error: { statusCode: 503 } },
    }),
    {
      source: "SDK_CLOSE",
      component: "TTS",
      code: "HTTP_5XX",
      statusCode: 503,
    },
  );
  assert.deepEqual(
    mediaFailure("ROOM_DISCONNECTED", {
      error: {
        type: "PRIVATE_TYPE",
        recoverable: "PRIVATE",
        error: { statusCode: "403" },
      },
    }),
    { source: "ROOM_DISCONNECTED", component: "OTHER" },
  );
  for (const statusCode of [399, 600, 2007, -1, Number.NaN, 429.5, "429"]) {
    const failure = mediaFailure("SDK_ERROR", {
      error: { type: "stt_error", error: { statusCode } },
    });
    assert.equal(Object.hasOwn(failure, "statusCode"), false);
    assert.equal(Object.hasOwn(failure, "code"), false);
  }
});

void test("normal playback keeps the pinned SDK STT lifecycle intact until explicit close", async (t) => {
  initializeLogger({ pretty: false, level: "silent" });
  const session = new voice.AgentSession(sessionOptions);
  const output = new EventEmitter();
  Reflect.set(session.output, "_audioSink", output);
  const clears = t.mock.method(session, "clearUserTurn", () => undefined);
  const closes = t.mock.method(session, "close", () => Promise.resolve());
  const utterances = t.mock.method(session, "say", () => {
    queueMicrotask(() => output.emit(voice.AudioOutput.EVENT_PLAYBACK_STARTED));
    return {
      waitForPlayout: () => Promise.resolve(),
      exception: () => null,
      interrupt: () => undefined,
    } as unknown as voice.SpeechHandle;
  });
  let disconnected = 0;
  const context = {
    room: {
      remoteParticipants: new Map(),
      disconnect: () => {
        disconnected++;
        return Promise.resolve();
      },
    },
  } as unknown as JobContext;
  const media = new LiveKitMedia(
    context,
    {
      voiceSessionId: randomUUID(),
      attemptId: randomUUID(),
      dispatchId: randomUUID(),
    },
    loadConfiguration({}),
  );
  Reflect.set(media, "session", session);
  Reflect.set(media, "started", true);
  const pauses: number[] = [];
  Reflect.set(media, "pause", (ms: number) => {
    pauses.push(ms);
    return Promise.resolve();
  });
  let published = 0;
  for (let turn = 0; turn < 2; turn++) {
    await media.speak(
      "Synthetic response. Second part.",
      () => {
        published++;
        return Promise.resolve();
      },
      new AbortController().signal,
    );
    assert.equal(clears.mock.callCount(), 0);
    assert.equal(closes.mock.callCount(), 0);
    assert.equal(session.output.audio, output);
  }
  assert.equal(published, 2);
  assert.equal(utterances.mock.callCount(), 4);
  assert.deepEqual(
    utterances.mock.calls.map((call) => call.arguments[0]),
    [
      "Synthetic response.",
      "Second part.",
      "Synthetic response.",
      "Second part.",
    ],
  );
  assert.equal(pauses.length, 3);
  assert.equal(pauses[0], sentencePauseMs);
  assert.ok(pauses[1] !== undefined && pauses[1] > 0);
  assert.ok(pauses[1] <= sentencePauseMs);
  assert.equal(pauses[2], sentencePauseMs);
  assert.deepEqual(utterances.mock.calls[0]?.arguments[1], {
    allowInterruptions: true,
    addToChatCtx: false,
  });
  await media.close();
  assert.equal(clears.mock.callCount(), 0);
  assert.equal(closes.mock.callCount(), 1);
  assert.equal(disconnected, 1);
  assert.deepEqual(sttModelOptions, {
    endpointing: 500,
    interim_results: true,
    punctuate: true,
  });
});

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

void test("TTS timing retains finite SDK first-byte and synthesis duration without inventing missing values", () => {
  const raw = {
    type: "tts_metrics",
    audioDurationMs: 1200,
    charactersCount: 40,
    cancelled: false,
    ttfbMs: 210,
    durationMs: 750,
    text: "PRIVATE_CONTENT",
    requestId: "PRIVATE_KEY",
  };
  assert.deepEqual(speechUsage(raw), {
    phase: "speech_usage",
    component: "TTS",
    audioDurationMs: 1200,
    charactersCount: 40,
    cancelled: false,
    ttsFirstByteMs: 210,
    synthesisDurationMs: 750,
  });
  for (const missing of [-1, Number.NaN, Infinity]) {
    const value = speechUsage({ ...raw, ttfbMs: missing, durationMs: missing });
    assert.equal(value?.ttsFirstByteMs, undefined);
    assert.equal(value?.synthesisDurationMs, undefined);
    assert.equal(JSON.stringify(value).includes("PRIVATE"), false);
  }
  assert.equal(
    speechUsage({ ...raw, type: "stt_metrics" })?.ttsFirstByteMs,
    undefined,
  );
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
    "Synthetic response",
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
      "Synthetic response",
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
    "Synthetic response",
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
    "Synthetic response",
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

void test("sentence chunks split Vietnamese sentence ends but keep decimals and never emit empty chunks", () => {
  assert.deepEqual(
    sentenceChunks("Chào bạn! Hôm nay bạn thấy thế nào? Mình ở đây.  "),
    ["Chào bạn!", "Hôm nay bạn thấy thế nào?", "Mình ở đây."],
  );
  assert.deepEqual(sentenceChunks("Điểm của bạn là 3.5 trên 10. Ổn rồi."), [
    "Điểm của bạn là 3.5 trên 10.",
    "Ổn rồi.",
  ]);
  assert.deepEqual(
    sentenceChunks("Mình hiểu… Bạn kể tiếp nhé... Được không?"),
    ["Mình hiểu…", "Bạn kể tiếp nhé...", "Được không?"],
  );
  assert.deepEqual(sentenceChunks('Bạn nói "ổn." Rồi sao?'), [
    'Bạn nói "ổn."',
    "Rồi sao?",
  ]);
  assert.deepEqual(sentenceChunks("Một câu duy nhất"), ["Một câu duy nhất"]);
  assert.deepEqual(sentenceChunks("  Thở chậm lại nhé.\n"), [
    "Thở chậm lại nhé.",
  ]);
  assert.deepEqual(sentenceChunks(" \n "), []);
  assert.deepEqual(sentenceChunks("Mình ở TP. Hồ Chí Minh. Còn bạn?"), [
    "Mình ở TP. Hồ Chí Minh.",
    "Còn bạn?",
  ]);
  assert.deepEqual(sentenceChunks("Mình ở TP. HCM. Còn bạn?"), [
    "Mình ở TP. HCM. Còn bạn?",
  ]);
  assert.deepEqual(sentenceChunks("Hỏi ThS. Lan hoặc BS. Minh nhé."), [
    "Hỏi ThS. Lan hoặc BS. Minh nhé.",
  ]);
  assert.deepEqual(sentenceChunks("Đi bộ, nghe nhạc, v.v. đều tốt. Thử nhé."), [
    "Đi bộ, nghe nhạc, v.v. đều tốt.",
    "Thử nhé.",
  ]);
  assert.deepEqual(
    sentenceChunks("1. Hít thở sâu. 2. Thả lỏng vai.\n3. Nghỉ."),
    ["1. Hít thở sâu.", "2. Thả lỏng vai.", "3. Nghỉ."],
  );
  assert.deepEqual(sentenceChunks("Có 3. Bạn thử nhé."), [
    "Có 3.",
    "Bạn thử nhé.",
  ]);
  assert.deepEqual(sentenceChunks("Ừ. Mình hiểu."), ["Ừ.", "Mình hiểu."]);
  assert.deepEqual(ttsModelOptions, { speed: 0.9 });
});

void test("ElevenLabs speaks a whole unit in one Flash v2.5 generation; without it Cartesia stays", async () => {
  const livekit = {
    apiKey: "synthetic-key",
    apiSecret: "synthetic-livekit-secret-32-characters",
  };
  const base = loadConfiguration({
    AI_VOICE_TTS_VOICE_ID: "8e8f222d-c817-4cc5-822b-8bf76ca7e98d",
  });
  const eleven = speechTts(
    {
      ...base,
      elevenLabs: {
        apiKey: "sk_synthetic_elevenlabs_key_0001",
        voiceId: "Abc123Def456Ghi789Jk",
      },
    },
    livekit,
  );
  assert.ok(eleven instanceof elevenlabs.TTS);
  assert.equal(eleven.model, "eleven_flash_v2_5");
  await eleven.close();
  const cartesia = speechTts(base, livekit);
  assert.ok(cartesia instanceof inference.TTS);
  await cartesia.close();

  const said: string[] = [];
  const pauses: number[] = [];
  const output = new EventEmitter();
  await approvedPlayback(
    output,
    "Chào bạn. Mình nghe đây! Bạn kể nhé?",
    (chunk) => {
      said.push(chunk);
      output.emit(voice.AudioOutput.EVENT_PLAYBACK_STARTED);
      return { waitForPlayout: () => Promise.resolve(), exception: () => null };
    },
    () => Promise.resolve(),
    new AbortController().signal,
    () => assert.fail("unexpected interrupt"),
    () => assert.fail("unexpected failure"),
    (ms) => {
      pauses.push(ms);
      return Promise.resolve();
    },
    (whole) => [whole],
  );
  assert.deepEqual(said, ["Chào bạn. Mình nghe đây! Bạn kể nhé?"]);
  assert.deepEqual(pauses, []);
});

void test("playback speaks sentence chunks in order with a pause between them and none before the first", async () => {
  const output = new EventEmitter();
  const events: string[] = [];
  let published = 0;
  await approvedPlayback(
    output,
    "Chào bạn. Mình nghe đây! Bạn kể nhé?",
    (chunk) => {
      events.push(`say:${chunk}`);
      if (events.length === 1)
        output.emit(voice.AudioOutput.EVENT_PLAYBACK_STARTED);
      return {
        waitForPlayout: () => {
          events.push("playout");
          return Promise.resolve();
        },
        exception: () => null,
      };
    },
    () => {
      published++;
      return Promise.resolve();
    },
    new AbortController().signal,
    () => assert.fail("unexpected interrupt"),
    () => assert.fail("unexpected failure"),
    (ms) => {
      events.push(`pause:${String(ms)}`);
      return Promise.resolve();
    },
  );
  assert.equal(published, 1);
  assert.deepEqual(events, [
    "say:Chào bạn.",
    "playout",
    `pause:${String(sentencePauseMs)}`,
    "say:Mình nghe đây!",
    "playout",
    `pause:${String(sentencePauseMs)}`,
    "say:Bạn kể nhé?",
    "playout",
  ]);
});

void test("stop during a sentence pause or chunk prevents every later chunk", async () => {
  for (const stopAt of ["pause", "chunk"] as const) {
    const output = new EventEmitter();
    const abort = new AbortController();
    const said: string[] = [];
    let cuts = 0;
    await approvedPlayback(
      output,
      "Một. Hai. Ba.",
      (chunk) => {
        said.push(chunk);
        if (said.length === 1)
          output.emit(voice.AudioOutput.EVENT_PLAYBACK_STARTED);
        return {
          waitForPlayout: () => {
            if (stopAt === "chunk" && said.length === 2) abort.abort();
            return Promise.resolve();
          },
          exception: () => null,
        };
      },
      () => Promise.resolve(),
      abort.signal,
      () => {
        cuts++;
      },
      () => assert.fail("unexpected failure"),
      (ms, signal) => {
        if (stopAt === "pause") abort.abort();
        return silence(ms, signal);
      },
    );
    assert.deepEqual(said, stopAt === "pause" ? ["Một."] : ["Một.", "Hai."]);
    assert.equal(cuts, 1);
  }
  const stopped = new AbortController();
  const startedAt = performance.now();
  const pause = silence(60000, stopped.signal);
  stopped.abort();
  await pause;
  assert.ok(performance.now() - startedAt < 1000);
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

void test("input audio levels aggregate numeric PCM windows and pass identical frames without retaining content", async () => {
  const known = new Int16Array(8000);
  known.fill(16384, 2000, 4000);
  known.fill(-16384, 4000, 6000);
  known.fill(-32768, 6000);
  const tail = new Int16Array(4000).fill(16384);
  const frames = [
    new AudioFrame(known, 8000, 1, 8000, {
      transcript: "PRIVATE_TRANSCRIPT",
      device: "PRIVATE_DEVICE",
    }),
    new AudioFrame(tail, 8000, 1, 4000),
    new AudioFrame(new Int16Array([1]), Number.NaN, 1, 1),
  ];
  const snapshots = frames.map((frame) => new Int16Array(frame.data));
  const diagnostics: AgentDiagnostic[] = [];
  const stream = new ReadableStream<AudioFrame>({
    start(controller) {
      for (const frame of frames) controller.enqueue(frame);
      controller.close();
    },
  });
  const forwarded = [];
  for await (const frame of inputAudioLevel(stream, (value) =>
    diagnostics.push(value),
  ))
    forwarded.push(frame);
  assert.equal(forwarded.length, frames.length);
  for (const [index, frame] of frames.entries()) {
    assert.equal(forwarded[index], frame);
    assert.equal(forwarded[index].data, frame.data);
    assert.deepEqual(frame.data, snapshots[index]);
  }
  assert.deepEqual(diagnostics, [
    {
      phase: "input_audio_level",
      component: "STT",
      rms: 0.612372,
      peakAmplitude: 1,
      audioDurationMs: 1000,
    },
    {
      phase: "input_audio_level",
      component: "STT",
      rms: 0.5,
      peakAmplitude: 0.5,
      audioDurationMs: 500,
    },
  ]);
  assert.equal(JSON.stringify(diagnostics).includes("PRIVATE"), false);
  assert.equal(JSON.stringify(diagnostics).includes("data"), false);
  for (const diagnostic of diagnostics) {
    assert.ok(Number.isFinite(diagnostic.audioDurationMs));
    for (const value of [diagnostic.rms, diagnostic.peakAmplitude])
      assert.ok(typeof value === "number" && value >= 0 && value <= 1);
  }
});
