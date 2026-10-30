import { createHash, randomUUID } from "node:crypto";
import { AgentFailure } from "./configuration.js";
import type { Dispatch, Lease, Reply, Signal, StopReason } from "./contract.js";
import type { JournalClient } from "./journal-client.js";
import type { AgentDiagnostic } from "./diagnostics.js";

export type AgentState =
  | "CONNECTING"
  | "LISTENING"
  | "TRANSCRIBING"
  | "WAITING_FOR_REPLY"
  | "SPEAKING"
  | "INTERRUPTED"
  | "MUTED"
  | "DISCONNECTED"
  | "ENDING"
  | "ENDED";
export interface Transcript {
  text: string;
  final: boolean;
  language: string;
  confidence: number;
  segmentId: string;
  durationMs: number;
}
export interface MediaCallbacks {
  transcript: (value: Transcript) => void;
  muted: (value: boolean) => void;
  connected: (value: boolean) => void;
  failed: () => void;
}
export interface MediaPort {
  state(state: AgentState): void;
  start(callbacks: MediaCallbacks): Promise<void>;
  input(enabled: boolean): void;
  clearInput(): void;
  speak(
    text: string,
    started: () => Promise<void>,
    signal: AbortSignal,
  ): Promise<void>;
  interrupt(): void;
  close(): Promise<void>;
}
type Journal = Pick<JournalClient, "claim" | "control" | "turn">;
const digest = (value: string) =>
  createHash("sha256").update(value).digest("hex");
const normalized = (value: string) =>
  value
    .normalize("NFC")
    .toLocaleLowerCase("vi")
    .replace(/[^\p{L}\p{N}\s]/gu, " ")
    .replace(/\s+/gu, " ")
    .trim();
export class VoiceWorker {
  state: AgentState = "CONNECTING";
  readonly finished: Promise<void>;
  private finish: () => void = () => undefined;
  private lease: Lease | null = null;
  private backendSequence = 0;
  private sequence = 0;
  private turnSequence = 0;
  private controls: Promise<unknown> = Promise.resolve();
  private readonly life = new AbortController();
  private readonly seen = new Set<string>();
  private pending = "";
  private pauseUntil = 0;
  private speaking: Reply | null = null;
  private playbackStarted = false;
  private interrupted = false;
  private playback: AbortController | null = null;
  private nextHeartbeat = 0;
  private renewing = false;
  private busy = false;
  private ready = false;
  private connected = true;
  private muted = false;
  private reconnectUntil = 0;
  private stopWork: Promise<void> | undefined;
  private lastEcho = "";
  private echoUntil = 0;
  constructor(
    private readonly dispatch: Dispatch,
    private readonly journal: Journal,
    private readonly media: MediaPort,
    private readonly clock: () => number = Date.now,
    private readonly outcome: (
      code: AgentState | "TEXT_OR_PUSH_TO_TALK",
    ) => void = () => undefined,
    private readonly diagnostic: (value: AgentDiagnostic) => void = () =>
      undefined,
  ) {
    this.finished = new Promise((resolve) => {
      this.finish = resolve;
    });
  }
  private setState(state: AgentState) {
    this.state = state;
    try {
      this.media.state(state);
    } catch {
      this.outcome(state);
      return;
    }
    this.outcome(state);
  }
  private live() {
    return (
      this.lease !== null &&
      !this.life.signal.aborted &&
      this.clock() <
        Math.min(
          Date.parse(this.lease.validUntil),
          Date.parse(this.lease.hardStopAt),
        )
    );
  }
  async start() {
    try {
      this.lease = await this.journal.claim(this.dispatch, this.life.signal);
      this.diagnostic({ phase: "agent_claim_ok" });
      this.scheduleHeartbeat();
      await this.media.start({
        transcript: (value) => {
          this.receive(value);
        },
        muted: (value) => {
          void this.mute(value).catch(() => this.fail());
        },
        connected: (value) => {
          void this.connection(value).catch(() => this.fail());
        },
        failed: () => {
          void this.fail();
        },
      });
      await this.control("MEDIA_READY", {});
      this.diagnostic({ phase: "media_ready_ack" });
      if (!this.live()) throw new AgentFailure("AUTHORITY_EXPIRED");
      this.ready = true;
      this.media.input(this.connected && !this.muted);
      this.setState(
        this.connected ? (this.muted ? "MUTED" : "LISTENING") : "DISCONNECTED",
      );
    } catch (error) {
      this.diagnostic({
        phase: "startup_failed",
        code:
          error instanceof AgentFailure
            ? (error.failure ?? "INVALID_RESPONSE")
            : "TRANSPORT",
      });
      await this.fail();
    }
  }
  private control(
    kind: Signal["kind"],
    payload: Omit<Signal["payload"], "leaseId">,
  ) {
    const work = this.controls.then(async () => {
      const lease = this.lease;
      if (!lease || !this.live()) throw new AgentFailure("AUTHORITY_EXPIRED");
      const now = this.clock();
      if (kind === "HEARTBEAT")
        this.diagnostic({
          phase: "heartbeat_attempt",
          remainingMs: Date.parse(lease.validUntil) - now,
        });
      const signal: Signal = {
        schemaVersion: 1,
        kind,
        commandId: randomUUID(),
        voiceSessionId: this.dispatch.voiceSessionId,
        attemptId: this.dispatch.attemptId,
        sequence: ++this.sequence,
        issuedAt: new Date(now).toISOString(),
        expiresAt: new Date(
          Math.min(Date.parse(lease.validUntil), now + 4000),
        ).toISOString(),
        payload: { leaseId: lease.leaseId, ...payload },
      };
      const result = await this.journal.control(
        this.dispatch,
        signal,
        kind === "FINISHED" ? undefined : this.life.signal,
      );
      if (!this.live()) throw new AgentFailure("AUTHORITY_EXPIRED");
      if (result.control.sequence <= this.backendSequence)
        throw new AgentFailure("INVALID_RESPONSE");
      this.backendSequence = result.control.sequence;
      if (result.action === "STOP") {
        void this.stop(result.control.payload.reason, false);
        throw new AgentFailure("AUTHORITY_EXPIRED");
      }
      const renewed = result.control.payload;
      if (
        renewed.leaseId !== lease.leaseId ||
        renewed.hardStopAt !== lease.hardStopAt ||
        result.control.expiresAt !== renewed.validUntil ||
        Date.parse(renewed.validUntil) <= this.clock() ||
        Date.parse(renewed.validUntil) > this.clock() + 10500 ||
        Date.parse(renewed.validUntil) > Date.parse(lease.hardStopAt)
      )
        throw new AgentFailure("INVALID_RESPONSE");
      this.lease = { ...lease, validUntil: renewed.validUntil };
      if (kind === "HEARTBEAT") this.diagnostic({ phase: "heartbeat_ack" });
      this.scheduleHeartbeat();
    });
    this.controls = work.catch(() => undefined);
    return work;
  }
  private scheduleHeartbeat() {
    if (!this.lease) return;
    const now = this.clock();
    this.nextHeartbeat = Math.max(
      now,
      Math.min(now + 5000, Date.parse(this.lease.validUntil) - 3000),
    );
    this.diagnostic({
      phase: "next_heartbeat_due",
      delayMs: this.nextHeartbeat - now,
      remainingMs: Date.parse(this.lease.validUntil) - now,
    });
  }
  receive(value: Transcript) {
    if (!this.ready || !this.live() || !this.connected || this.muted) return;
    const text = value.text.trim();
    if (!text) return;
    if (
      !Number.isFinite(value.confidence) ||
      value.confidence < 0.8 ||
      value.language !== "vi" ||
      text.length > 2000
    ) {
      if (value.final) void this.fail();
      return;
    }
    const clean = normalized(text);
    if (
      this.clock() < this.echoUntil &&
      this.lastEcho &&
      (this.lastEcho.includes(clean) || clean.includes(this.lastEcho))
    )
      return;
    if (!value.final) {
      if (
        this.speaking &&
        value.durationMs >= 500 &&
        clean.split(" ").length >= 2
      )
        void this.bargeIn();
      return;
    }
    const key = digest(value.segmentId);
    if (this.seen.has(key)) return;
    if (this.seen.size >= 256) {
      void this.fail();
      return;
    }
    this.seen.add(key);
    if (this.busy && !this.speaking) {
      void this.stop("USER_ENDED");
      return;
    }
    if (this.speaking) void this.bargeIn();
    this.pending = [this.pending, text].filter(Boolean).join(" ");
    if (Buffer.byteLength(this.pending, "utf8") > 1800) {
      void this.fail();
      return;
    }
    this.pauseUntil = this.clock() + 1500;
    this.setState("TRANSCRIBING");
  }
  async tick() {
    if (this.life.signal.aborted) return;
    if (!this.lease && this.state === "CONNECTING") return;
    if (!this.live()) {
      this.diagnostic({ phase: "lease_expired" });
      await this.stop("AUTHORIZATION_EXPIRED", false);
      return;
    }
    if (!this.connected && this.clock() >= this.reconnectUntil) {
      await this.stop("RECONNECT_TIMEOUT");
      return;
    }
    if (!this.renewing && this.clock() >= this.nextHeartbeat) {
      this.renewing = true;
      try {
        await this.control("HEARTBEAT", {
          observedState:
            this.state === "CONNECTING"
              ? "CONNECTING"
              : this.connected
                ? "ACTIVE"
                : "RECONNECTING",
        });
      } catch (error) {
        this.diagnostic({
          phase: "heartbeat_fail",
          code:
            error instanceof AgentFailure
              ? (error.failure ??
                (error.code === "AUTHORITY_EXPIRED"
                  ? "AUTHORITY_EXPIRED"
                  : "INVALID_RESPONSE"))
              : "TRANSPORT",
        });
        await this.stop("AUTHORIZATION_EXPIRED", false);
      } finally {
        this.renewing = false;
      }
    }
    if (
      this.pending &&
      !this.busy &&
      this.connected &&
      !this.muted &&
      this.clock() >= this.pauseUntil
    ) {
      const text = this.pending;
      this.pending = "";
      await this.turn(text);
    }
  }
  private async turn(text: string) {
    if (!this.live() || !this.lease) return;
    this.busy = true;
    this.setState("WAITING_FOR_REPLY");
    this.media.clearInput();
    try {
      this.diagnostic({ phase: "turn_requested" });
      const reply = await this.journal.turn(
        this.dispatch,
        {
          attemptId: this.dispatch.attemptId,
          leaseId: this.lease.leaseId,
          turnId: randomUUID(),
          sequence: ++this.turnSequence,
          language: "vi-VN",
          isFinal: true,
          finalTranscript: text,
        },
        randomUUID(),
        this.life.signal,
      );
      if (
        !this.live() ||
        Date.parse(reply.expiresAt) > Date.parse(this.lease.validUntil) ||
        Date.parse(reply.expiresAt) <= this.clock()
      )
        throw new AgentFailure("AUTHORITY_EXPIRED");
      this.speaking = reply;
      this.interrupted = false;
      this.playbackStarted = false;
      this.playback = new AbortController();
      this.lastEcho = normalized(reply.spokenText);
      this.echoUntil = this.clock() + 60000;
      this.diagnostic({ phase: "tts_begin" });
      await this.media.speak(
        reply.spokenText,
        async () => {
          this.diagnostic({ phase: "playout_first_audio" });
          if (
            !this.live() ||
            !this.connected ||
            this.muted ||
            Date.parse(reply.expiresAt) <= this.clock()
          )
            throw new AgentFailure("AUTHORITY_EXPIRED");
          await this.control("PLAYBACK_STARTED", {
            turnId: reply.turnId,
            replyId: reply.replyId,
          });
          this.playbackStarted = true;
          if (this.interrupted) {
            this.playbackStarted = false;
            await this.control("INTERRUPTED", {
              turnId: reply.turnId,
              replyId: reply.replyId,
            });
          } else this.setState("SPEAKING");
        },
        AbortSignal.any([this.life.signal, this.playback.signal]),
      );
      if (this.life.signal.aborted) return;
      this.echoUntil = this.clock() + 1000;
      this.speaking = null;
      this.playback = null;
      if (!this.pending)
        this.setState(
          this.muted ? "MUTED" : this.connected ? "LISTENING" : "DISCONNECTED",
        );
    } catch (error) {
      this.diagnostic({
        phase: "turn_failed",
        code:
          error instanceof AgentFailure
            ? (error.failure ?? "INVALID_RESPONSE")
            : "PROVIDER_FAILED",
      });
      if (this.life.signal.aborted) return;
      if (!this.interrupted) await this.fail();
    } finally {
      this.busy = false;
      this.speaking = null;
      this.playback = null;
    }
  }
  private async bargeIn() {
    const reply = this.speaking;
    if (!reply || !this.playback) return;
    this.media.interrupt();
    this.interrupted = true;
    this.playback.abort();
    this.setState(
      this.muted ? "MUTED" : this.connected ? "INTERRUPTED" : "DISCONNECTED",
    );
    if (this.playbackStarted) {
      this.playbackStarted = false;
      try {
        await this.control("INTERRUPTED", {
          turnId: reply.turnId,
          replyId: reply.replyId,
        });
      } catch {
        await this.fail();
      }
    }
  }
  private async mute(value: boolean) {
    this.muted = value;
    this.pending = "";
    this.media.clearInput();
    this.media.input(this.ready && this.live() && this.connected && !value);
    if (value && this.speaking) await this.bargeIn();
    if (this.ready && !this.life.signal.aborted)
      this.setState(
        !this.connected ? "DISCONNECTED" : this.muted ? "MUTED" : "LISTENING",
      );
  }
  private async connection(value: boolean) {
    if (this.life.signal.aborted || value === this.connected) return;
    if (value && this.clock() >= this.reconnectUntil) {
      await this.stop("RECONNECT_TIMEOUT");
      return;
    }
    this.connected = value;
    this.pending = "";
    this.media.input(false);
    this.media.clearInput();
    if (!value) {
      this.reconnectUntil = this.clock() + 30000;
      if (this.busy) {
        await this.stop("AGENT_FAILURE");
        return;
      }
      this.setState("DISCONNECTED");
      return;
    }
    try {
      await this.control("HEARTBEAT", { observedState: "RECONNECTING" });
      if (this.live()) {
        this.media.input(this.ready && !this.muted);
        this.setState(
          this.ready ? (this.muted ? "MUTED" : "LISTENING") : "CONNECTING",
        );
      }
    } catch {
      await this.stop("AUTHORIZATION_EXPIRED", false);
    }
  }
  async fail() {
    this.outcome("TEXT_OR_PUSH_TO_TALK");
    await this.stop("PROVIDER_FAILURE");
  }
  stop(reason: StopReason, notify = true): Promise<void> {
    if (this.stopWork) return this.stopWork;
    const lease = this.lease;
    const notifyAllowed = notify && this.live();
    this.setState("ENDING");
    this.diagnostic({ phase: "agent_stopped", reason });
    this.ready = false;
    this.pending = "";
    this.seen.clear();
    this.lastEcho = "";
    this.speaking = null;
    this.playback?.abort();
    this.life.abort();
    for (const cut of [
      () => {
        this.media.input(false);
      },
      () => {
        this.media.interrupt();
      },
    ]) {
      try {
        cut();
      } catch {
        this.outcome("TEXT_OR_PUSH_TO_TALK");
      }
    }
    this.stopWork = (async () => {
      try {
        await this.media.close();
      } catch {
        this.outcome("TEXT_OR_PUSH_TO_TALK");
      }
      if (notifyAllowed && lease) {
        try {
          await this.controls;
          const now = this.clock();
          if (now < Date.parse(lease.validUntil))
            await this.journal.control(this.dispatch, {
              schemaVersion: 1,
              kind: "FINISHED",
              commandId: randomUUID(),
              voiceSessionId: this.dispatch.voiceSessionId,
              attemptId: this.dispatch.attemptId,
              sequence: ++this.sequence,
              issuedAt: new Date(now).toISOString(),
              expiresAt: lease.validUntil,
              payload: { leaseId: lease.leaseId, reason },
            });
        } catch {
          this.outcome("TEXT_OR_PUSH_TO_TALK");
        }
      }
    })().finally(() => {
      this.lease = null;
      this.setState("ENDED");
      this.finish();
    });
    return this.stopWork;
  }
}
export function startWorkerTimer(worker: Pick<VoiceWorker, "tick" | "fail">) {
  const timer = setInterval(() => {
    void worker.tick().catch(() => worker.fail());
  }, 250);
  timer.unref();
  return () => {
    clearInterval(timer);
  };
}
