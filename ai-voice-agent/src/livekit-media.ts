import { createHash } from "node:crypto";
import { TransformStream } from "node:stream/web";
import {
  AutoSubscribe,
  initializeLogger,
  stt,
  voice,
  inference,
  type JobContext,
} from "@livekit/agents";
import { VAD } from "@livekit/agents-plugin-silero";
import {
  ParticipantKind,
  RoomEvent,
  TrackKind,
  TrackSource,
  type RemoteParticipant,
} from "@livekit/rtc-node";
import { AgentFailure, type AgentConfiguration } from "./configuration.js";
import type { Dispatch } from "./contract.js";
import type { MediaCallbacks, MediaPort } from "./voice-worker.js";

export const sessionOptions = {
  turnHandling: {
    turnDetection: "manual" as const,
    interruption: { enabled: false, resumeFalseInterruption: false },
    preemptiveGeneration: { enabled: false, preemptiveTts: false },
  },
  userAwayTimeout: null,
  transcriptionTimeout: null,
  ttsFrameTimeout: 3000,
  connOptions: {
    sttConnOptions: { maxRetry: 0, timeoutMs: 3000, retryIntervalMs: 0 },
    ttsConnOptions: { maxRetry: 0, timeoutMs: 3000, retryIntervalMs: 0 },
    maxUnrecoverableErrors: 1,
  },
};
export const startOptions = {
  record: false,
  inputOptions: {
    audioEnabled: false,
    textEnabled: false,
    videoEnabled: false,
    closeOnDisconnect: false,
    deleteRoomOnClose: false,
  },
  outputOptions: {
    audioEnabled: true,
    transcriptionEnabled: false,
    syncTranscription: false,
  },
};
export function transcriptFilter(receive: MediaCallbacks["transcript"]) {
  return new TransformStream<
    stt.SpeechEvent | string,
    stt.SpeechEvent | string
  >({
    transform(event, controller) {
      if (typeof event === "string") return;
      if (
        event.type === stt.SpeechEventType.FINAL_TRANSCRIPT ||
        event.type === stt.SpeechEventType.INTERIM_TRANSCRIPT
      ) {
        const first = event.alternatives?.[0];
        if (first)
          receive({
            text: first.text,
            final: event.type === stt.SpeechEventType.FINAL_TRANSCRIPT,
            language: first.language,
            confidence: first.confidence,
            segmentId: createHash("sha256")
              .update(
                `${String(first.startTime)}:${String(first.endTime)}:${first.text}`,
              )
              .digest("hex"),
            durationMs: Math.max(0, (first.endTime - first.startTime) * 1000),
          });
        return;
      }
      const safeEvent = { ...event };
      delete safeEvent.alternatives;
      controller.enqueue(safeEvent);
    },
  });
}
export async function approvedPlayback(
  output: {
    once(
      event: typeof voice.AudioOutput.EVENT_PLAYBACK_STARTED,
      listener: () => void,
    ): unknown;
    off(
      event: typeof voice.AudioOutput.EVENT_PLAYBACK_STARTED,
      listener: () => void,
    ): unknown;
  },
  say: () => Pick<voice.SpeechHandle, "waitForPlayout" | "exception">,
  started: () => Promise<void>,
  signal: AbortSignal,
  interrupt: () => void,
  failed: () => void,
) {
  if (signal.aborted) throw new AgentFailure("AUTHORITY_EXPIRED");
  let publication: Promise<void> | undefined;
  const onStart = () => {
    publication ??= started();
    void publication.catch(() => {
      interrupt();
      failed();
    });
  };
  output.once(voice.AudioOutput.EVENT_PLAYBACK_STARTED, onStart);
  signal.addEventListener("abort", interrupt, { once: true });
  try {
    const speech = say();
    await speech.waitForPlayout();
    await verifyPublication({ publication }, signal);
    if (speech.exception()) throw new AgentFailure("PROVIDER_FAILED");
  } finally {
    output.off(voice.AudioOutput.EVENT_PLAYBACK_STARTED, onStart);
    signal.removeEventListener("abort", interrupt);
  }
}
async function verifyPublication(
  value: { publication: Promise<void> | undefined },
  signal: AbortSignal,
) {
  if (value.publication) await value.publication;
  else if (!signal.aborted) throw new AgentFailure("PROVIDER_FAILED");
}
export class LiveKitMedia implements MediaPort {
  private session: voice.AgentSession | undefined;
  private agent: voice.Agent | undefined;
  private speech: voice.SpeechHandle | undefined;
  private callbacks: MediaCallbacks | undefined;
  private owner: string | undefined;
  private closed = false;
  private readonly cleanup: (() => void)[] = [];
  constructor(
    private readonly context: JobContext,
    private readonly dispatch: Dispatch,
    private readonly configuration: AgentConfiguration,
  ) {}
  async start(callbacks: MediaCallbacks) {
    initializeLogger({ pretty: false, level: "silent" });
    this.callbacks = callbacks;
    const context = this.context;
    const guard = () => {
      if (
        context.room.isRecording ||
        context.room.metadata !== this.dispatch.dispatchId
      ) {
        callbacks.failed();
        return;
      }
      const people = [...context.room.remoteParticipants.values()];
      if (
        people.length > 1 ||
        people.some(
          (p) =>
            p.kind !== ParticipantKind.STANDARD ||
            !/^vu-[0-9a-f-]{36}$/u.test(p.identity) ||
            [...p.trackPublications.values()].some(
              (t) =>
                t.kind !== TrackKind.KIND_AUDIO ||
                t.source !== TrackSource.SOURCE_MICROPHONE,
            ),
        )
      ) {
        callbacks.failed();
        return;
      }
      const person = people[0];
      if (person && this.owner && person.identity !== this.owner) {
        callbacks.failed();
        return;
      }
      if (person) this.owner = person.identity;
    };
    context.room.on(RoomEvent.ParticipantConnected, guard);
    context.room.on(RoomEvent.TrackPublished, guard);
    context.room.on(RoomEvent.RoomMetadataChanged, guard);
    context.room.on(RoomEvent.RoomUpdated, guard);
    this.cleanup.push(() => {
      context.room.off(RoomEvent.ParticipantConnected, guard);
      context.room.off(RoomEvent.TrackPublished, guard);
      context.room.off(RoomEvent.RoomMetadataChanged, guard);
      context.room.off(RoomEvent.RoomUpdated, guard);
    });
    await context.connect(undefined, AutoSubscribe.SUBSCRIBE_NONE);
    if (context.agent?.identity !== `va-${this.dispatch.attemptId}`)
      throw new AgentFailure("DISPATCH_INVALID");
    guard();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const person = await Promise.race([
      context.waitForParticipant(),
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => {
          reject(new AgentFailure("PROVIDER_FAILED"));
        }, 5000);
        timer.unref();
      }),
    ]).finally(() => {
      if (timer) clearTimeout(timer);
    });
    guard();
    if (this.closed || this.owner !== person.identity)
      throw new AgentFailure("DISPATCH_INVALID");
    const vad = await VAD.load({
      minSpeechDuration: 0.2,
      minSilenceDuration: 1.5,
      maxBufferedSpeech: 15,
      forceCPU: true,
    });
    if (this.isClosed()) throw new AgentFailure("AUTHORITY_EXPIRED");
    const { apiKey, apiSecret, voiceId } = this.configuration;
    if (!apiKey || !apiSecret || !voiceId)
      throw new AgentFailure("CONFIGURATION_INVALID");
    const sttModel = new inference.STT({
      model: this.configuration.sttModel,
      language: "vi",
      apiKey,
      apiSecret,
      modelOptions: {
        endpointing: 1500,
        interim_results: true,
        punctuate: true,
      },
      connOptions: { maxRetry: 0, timeoutMs: 3000, retryIntervalMs: 0 },
    });
    const ttsModel = new inference.TTS({
      model: this.configuration.ttsModel,
      language: "vi",
      voice: voiceId,
      apiKey,
      apiSecret,
      connOptions: { maxRetry: 0, timeoutMs: 3000, retryIntervalMs: 0 },
    });
    this.session = new voice.AgentSession({
      ...sessionOptions,
      vad,
      stt: sttModel,
      tts: ttsModel,
    });
    this.agent = voice.Agent.create({
      instructions: "",
      llm: null,
      async *sttNode(ctx, audio, settings) {
        const events = await voice.Agent.default.sttNode(
          ctx.agent,
          audio,
          settings,
        );
        if (!events) return;
        const filtered = events.pipeThrough(
          transcriptFilter(callbacks.transcript),
        );
        for await (const event of filtered) yield event;
      },
      llmNode: () => null,
    });
    this.session.on(voice.AgentSessionEventTypes.Error, () => {
      callbacks.failed();
    });
    this.session.on(voice.AgentSessionEventTypes.Close, () => {
      if (!this.closed) callbacks.failed();
    });
    const disconnected = (p: RemoteParticipant) => {
      if (p.identity === this.owner) callbacks.connected(false);
    };
    const connected = (p: RemoteParticipant) => {
      guard();
      if (p.identity === this.owner) callbacks.connected(true);
    };
    const ownDisconnect = () => {
      callbacks.failed();
    };
    const muted = (_track: unknown, p: { identity: string }) => {
      if (p.identity === this.owner) callbacks.muted(true);
    };
    const unmuted = (_track: unknown, p: { identity: string }) => {
      if (p.identity === this.owner) callbacks.muted(false);
    };
    context.room.on(RoomEvent.ParticipantDisconnected, disconnected);
    context.room.on(RoomEvent.ParticipantConnected, connected);
    context.room.on(RoomEvent.Reconnecting, ownDisconnect);
    context.room.on(RoomEvent.Disconnected, ownDisconnect);
    context.room.on(RoomEvent.TrackMuted, muted);
    context.room.on(RoomEvent.TrackUnmuted, unmuted);
    this.cleanup.push(() => {
      context.room.off(RoomEvent.ParticipantDisconnected, disconnected);
      context.room.off(RoomEvent.ParticipantConnected, connected);
      context.room.off(RoomEvent.Reconnecting, ownDisconnect);
      context.room.off(RoomEvent.Disconnected, ownDisconnect);
      context.room.off(RoomEvent.TrackMuted, muted);
      context.room.off(RoomEvent.TrackUnmuted, unmuted);
    });
    await this.session.start({
      ...startOptions,
      agent: this.agent,
      room: context.room,
      inputOptions: {
        ...startOptions.inputOptions,
        participantIdentity: person.identity,
        participantKinds: [ParticipantKind.STANDARD],
      },
    });
    if (this.isClosed()) {
      await this.session.close();
      throw new AgentFailure("AUTHORITY_EXPIRED");
    }
    callbacks.muted(
      [...person.trackPublications.values()].some((t) => t.muted),
    );
  }
  input(enabled: boolean) {
    this.session?.input.setAudioEnabled(enabled && !this.closed);
  }
  private isClosed() {
    return this.closed;
  }
  clearInput() {
    this.session?.clearUserTurn();
  }
  async speak(text: string, started: () => Promise<void>, signal: AbortSignal) {
    const session = this.session;
    if (!session || this.closed || signal.aborted)
      throw new AgentFailure("AUTHORITY_EXPIRED");
    const output = session.output.audio;
    if (!output) throw new AgentFailure("PROVIDER_FAILED");
    try {
      await approvedPlayback(
        output,
        () => {
          this.speech = session.say(text, {
            allowInterruptions: true,
            addToChatCtx: false,
          });
          return this.speech;
        },
        started,
        signal,
        () => {
          this.interrupt();
        },
        () => {
          this.callbacks?.failed();
        },
      );
    } finally {
      this.speech = undefined;
      this.clearInput();
    }
  }
  interrupt() {
    this.speech?.interrupt(true);
  }
  async close() {
    if (this.closed) return;
    this.closed = true;
    this.input(false);
    this.interrupt();
    this.clearInput();
    for (const callback of this.cleanup.splice(0)) callback();
    await this.session?.close();
    this.session = undefined;
    this.agent = undefined;
    this.callbacks = undefined;
    await this.context.room.disconnect();
  }
}
