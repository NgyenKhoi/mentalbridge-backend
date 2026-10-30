import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import test from "node:test";
import type {
  Control,
  Dispatch,
  Lease,
  Reply,
  Signal,
  Turn,
} from "./contract.js";
import { policyVersion } from "./contract.js";
import type { JournalClient } from "./journal-client.js";
import {
  VoiceWorker,
  startWorkerTimer,
  type MediaCallbacks,
  type MediaPort,
  type Transcript,
} from "./voice-worker.js";
import { agentDiagnostics, type AgentDiagnostic } from "./diagnostics.js";
const hold = () => {
  let release: () => void = () => undefined;
  const wait = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { wait, release };
};
const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 10));
function fixture() {
  let now = Date.now();
  const hardStopAt = new Date(now + 300000).toISOString();
  const dispatch: Dispatch = {
    voiceSessionId: randomUUID(),
    attemptId: randomUUID(),
    dispatchId: randomUUID(),
  };
  const leaseId = randomUUID();
  let expires = now + 10000;
  let backend = 0;
  const signals: Signal[] = [];
  const turns: Turn[] = [];
  const played: string[] = [];
  const states: string[] = [];
  const mediaStates: string[] = [];
  const diagnostics: AgentDiagnostic[] = [];
  let callbacks: MediaCallbacks | undefined;
  let input = false;
  let closes = 0;
  let interrupted = 0;
  let claimed = true;
  let failControl = false;
  let stopControl = false;
  let duplicateControl = false;
  let generation: Promise<void> | undefined;
  let play: Promise<void> | undefined;
  let publication: Promise<void> | undefined;
  let readiness: Promise<void> | undefined;
  let playingReject: ((error: Error) => void) | undefined;
  const journal: Pick<JournalClient, "claim" | "control" | "turn"> = {
    claim: () =>
      claimed
        ? Promise.resolve({
            voiceSessionId: dispatch.voiceSessionId,
            attemptId: dispatch.attemptId,
            leaseId,
            validUntil: new Date(expires).toISOString(),
            hardStopAt,
            policyVersion,
          } satisfies Lease)
        : Promise.reject(new Error("UNAUTHENTICATED")),
    control: (_dispatch, signal) => {
      signals.push(signal);
      if (failControl)
        return Promise.reject(new Error("PRIVATE_OWNER_PAYLOAD"));
      expires = Math.min(now + 10000, Date.parse(hardStopAt));
      const base = {
        schemaVersion: 1 as const,
        commandId: randomUUID(),
        voiceSessionId: dispatch.voiceSessionId,
        attemptId: dispatch.attemptId,
        sequence: duplicateControl ? backend : ++backend,
        issuedAt: new Date(now).toISOString(),
        expiresAt: new Date(expires).toISOString(),
      };
      const value: Control = stopControl
        ? {
            action: "STOP",
            control: {
              ...base,
              kind: "STOP",
              payload: { reason: "CONSENT_REVOKED" },
            },
          }
        : {
            action: "CONTINUE",
            control: {
              ...base,
              kind: "LEASE_RENEWED",
              payload: {
                leaseId,
                validUntil: new Date(expires).toISOString(),
                hardStopAt,
                admittedSeconds: 300,
              },
            },
          };
      if (signal.kind === "MEDIA_READY" && readiness)
        return readiness.then(() => value);
      return signal.kind === "PLAYBACK_STARTED" && publication
        ? publication.then(() => value)
        : Promise.resolve(value);
    },
    turn: async (_dispatch, turn, _key, signal) => {
      turns.push(turn);
      if (generation)
        await Promise.race([
          generation,
          new Promise<never>((_resolve, reject) => {
            signal.addEventListener(
              "abort",
              () => {
                reject(new Error("CANCELLED"));
              },
              { once: true },
            );
          }),
        ]);
      return {
        voiceSessionId: dispatch.voiceSessionId,
        turnId: turn.turnId,
        replyId: randomUUID(),
        language: "vi-VN",
        spokenText: "Mình đang lắng nghe. Bạn muốn chia sẻ thêm điều gì?",
        expiresAt: new Date(expires).toISOString(),
        policyVersion,
      } satisfies Reply;
    },
  };
  const media: MediaPort = {
    state: (state) => {
      mediaStates.push(state);
    },
    start: (value) => {
      callbacks = value;
      callbacks.muted(false);
      return Promise.resolve();
    },
    input: (value) => {
      input = value;
    },
    clearInput: () => undefined,
    speak: async (text, started, signal) => {
      played.push(text);
      await started();
      if (play)
        await Promise.race([
          play,
          new Promise<never>((_resolve, reject) => {
            playingReject = reject;
            signal.addEventListener(
              "abort",
              () => {
                reject(new Error("INTERRUPTED"));
              },
              { once: true },
            );
          }),
        ]);
    },
    interrupt: () => {
      interrupted++;
      playingReject?.(new Error("INTERRUPTED"));
    },
    close: () => {
      closes++;
      return Promise.resolve();
    },
  };
  const worker = new VoiceWorker(
    dispatch,
    journal,
    media,
    () => now,
    (state) => states.push(state),
    (value) => diagnostics.push(value),
  );
  return {
    worker,
    signals,
    turns,
    played,
    states,
    mediaStates,
    diagnostics,
    dispatch,
    media,
    start: () => worker.start(),
    advance: (ms: number) => {
      now += ms;
    },
    tick: () => worker.tick(),
    input: () => input,
    closes: () => closes,
    interrupted: () => interrupted,
    emit: (text: string, partial: Partial<Transcript> = {}) => {
      assert.ok(callbacks);
      callbacks.transcript({
        text,
        final: true,
        language: "vi",
        confidence: 0.95,
        segmentId: randomUUID(),
        durationMs: 1200,
        ...partial,
      });
    },
    mute: (value: boolean) => {
      callbacks?.muted(value);
    },
    connection: (value: boolean) => {
      callbacks?.connected(value);
    },
    fail: () => {
      callbacks?.failed();
    },
    noClaim: () => {
      claimed = false;
    },
    badControl: () => {
      failControl = true;
    },
    revoke: () => {
      stopControl = true;
    },
    replayControl: () => {
      duplicateControl = true;
    },
    generation: (value: Promise<void>) => {
      generation = value;
    },
    play: (value: Promise<void>) => {
      play = value;
    },
    publication: (value: Promise<void>) => {
      publication = value;
    },
    readiness: (value: Promise<void>) => {
      readiness = value;
    },
  };
}

void test("production timer ticks every 250 ms, catches failures and stops scheduling", async (t) => {
  t.mock.timers.enable({ apis: ["setInterval"] });
  let ticks = 0;
  let failures = 0;
  const stop = startWorkerTimer({
    tick: () => {
      ticks++;
      return Promise.reject(new Error("PRIVATE"));
    },
    fail: () => {
      failures++;
      return Promise.resolve();
    },
  });
  t.mock.timers.tick(750);
  await Promise.resolve();
  assert.equal(ticks, 3);
  assert.equal(failures, 3);
  stop();
  t.mock.timers.tick(1000);
  assert.equal(ticks, 3);
});

void test("first heartbeat follows claim/readiness deadlines; jitter and slow queued acknowledgement cannot resurrect expired leases", async () => {
  const f = fixture();
  const gate = hold();
  f.readiness(gate.wait);
  const startup = f.start();
  await flush();
  f.advance(2000);
  await f.tick();
  assert.equal(f.signals.length, 1);
  gate.release();
  await startup;
  assert.equal(
    f.diagnostics.findLast((d) => d.phase === "next_heartbeat_due")?.delayMs,
    5000,
  );
  f.advance(4999);
  await f.tick();
  assert.equal(f.signals.length, 1);
  f.advance(251);
  await f.tick();
  assert.equal(f.signals.at(-1)?.kind, "HEARTBEAT");
  assert.equal(
    f.diagnostics.filter((d) => d.phase === "heartbeat_ack").length,
    1,
  );
  await f.worker.stop("USER_ENDED");

  const slow = fixture();
  const acknowledgement = hold();
  slow.readiness(acknowledgement.wait);
  const slowStart = slow.start();
  await flush();
  slow.advance(6000);
  acknowledgement.release();
  await slowStart;
  assert.equal(
    slow.diagnostics.findLast((d) => d.phase === "next_heartbeat_due")?.delayMs,
    1000,
  );
  slow.advance(1000);
  await slow.tick();
  assert.equal(slow.signals.at(-1)?.kind, "HEARTBEAT");
  await slow.worker.stop("USER_ENDED");

  const g = fixture();
  const delayed = hold();
  g.readiness(delayed.wait);
  const lateStart = g.start();
  await flush();
  g.advance(10001);
  const expiredTick = g.tick();
  delayed.release();
  await Promise.all([lateStart, expiredTick]);
  assert.equal(g.worker.state, "ENDED");
  assert.equal(g.input(), false);
  assert.ok(g.diagnostics.some((d) => d.phase === "lease_expired"));
  assert.equal(g.signals.filter((s) => s.kind === "HEARTBEAT").length, 0);
});

void test("heartbeat queue serializes slow publication and one renewal while ticks overlap", async () => {
  const f = fixture();
  await f.start();
  const gate = hold();
  f.publication(gate.wait);
  f.emit("Synthetic Vietnamese test");
  f.advance(1500);
  const turn = f.tick();
  await flush();
  f.advance(3500);
  const first = f.tick();
  const second = f.tick();
  assert.equal(f.signals.filter((s) => s.kind === "HEARTBEAT").length, 0);
  gate.release();
  await Promise.all([turn, first, second]);
  assert.deepEqual(
    f.signals.map((s) => s.sequence),
    [1, 2, 3],
  );
  assert.equal(f.signals.filter((s) => s.kind === "HEARTBEAT").length, 1);
  await f.worker.stop("USER_ENDED");
});

void test("backward wall-clock adjustment does not fabricate a heartbeat and a forward expiry cuts media without renewal", async () => {
  const f = fixture();
  await f.start();
  f.advance(-500);
  await f.tick();
  assert.equal(f.signals.length, 1);
  f.advance(5500);
  await f.tick();
  assert.equal(f.signals.at(-1)?.kind, "HEARTBEAT");
  f.advance(10001);
  await f.tick();
  assert.equal(f.worker.state, "ENDED");
  assert.equal(f.signals.filter((s) => s.kind === "HEARTBEAT").length, 1);
  assert.equal(f.input(), false);
});

void test("diagnostic records are bounded, monotonic and contain no conversation or credentials", () => {
  const records: object[] = [];
  let now = 10;
  const report = agentDiagnostics(
    randomUUID(),
    (r) => records.push(r),
    () => now++,
  );
  for (let i = 0; i < 600; i++) report({ phase: "heartbeat_ack" });
  assert.equal(records.length, 512);
  assert.deepEqual(Object.keys(records[0] ?? {}).sort(), [
    "attemptId",
    "elapsedMs",
    "event",
    "phase",
    "sequence",
  ]);
  assert.equal(JSON.stringify(records).includes("PRIVATE"), false);
});

void test("claims before audio and aggregates final Vietnamese segments, ignoring interim/duplicates", async () => {
  const f = fixture();
  await f.start();
  assert.equal(f.worker.state, "LISTENING");
  assert.equal(f.input(), true);
  assert.equal(f.signals[0]?.kind, "MEDIA_READY");
  f.emit("Tôi đang", { final: false, confidence: 0.1 });
  assert.equal(f.turns.length, 0);
  const id = randomUUID();
  f.emit("Tôi đang hơi lo", { segmentId: id });
  f.emit("Tôi đang hơi lo", { segmentId: id });
  f.advance(700);
  f.emit("vì bài kiểm tra");
  f.advance(1499);
  await f.tick();
  assert.equal(f.turns.length, 0);
  f.advance(1);
  await f.tick();
  assert.equal(f.turns.length, 1);
  assert.equal(f.turns[0]?.finalTranscript, "Tôi đang hơi lo vì bài kiểm tra");
  assert.equal(f.turns[0].language, "vi-VN");
  assert.equal(f.played.length, 1);
  assert.equal(
    f.signals.filter((s) => s.kind === "PLAYBACK_STARTED").length,
    1,
  );
  await f.worker.stop("USER_ENDED");
  assert.equal(f.input(), false);
  assert.equal(f.closes(), 1);
  assert.equal(f.worker.state, "ENDED");
  await f.worker.stop("USER_ENDED");
  assert.equal(f.closes(), 1);
});

void test("initial unmuted event cannot enable input or accept STT before MEDIA_READY acknowledgement", async () => {
  const f = fixture();
  const gate = hold();
  f.readiness(gate.wait);
  const startup = f.start();
  await flush();
  assert.equal(f.input(), false);
  f.emit("Không được nhận trước quyền media");
  assert.equal(f.turns.length, 0);
  gate.release();
  await startup;
  assert.equal(f.input(), true);
  await f.worker.stop("USER_ENDED");
});

void test("barge-in before publication acknowledgement still reports one interruption after the trusted start", async () => {
  const f = fixture();
  const acknowledgement = hold();
  const playback = hold();
  await f.start();
  f.publication(acknowledgement.wait);
  f.play(playback.wait);
  f.emit("Tôi đang hơi lo");
  f.advance(1500);
  const turn = f.tick();
  await flush();
  f.emit("Xin dừng lại", { final: false });
  acknowledgement.release();
  playback.release();
  await turn;
  assert.equal(f.signals.filter((s) => s.kind === "INTERRUPTED").length, 1);
  assert.equal(f.turns.length, 1);
  await f.worker.stop("USER_ENDED");
});

void test("media hints follow accepted turns and actual publication, without becoming quota evidence", async () => {
  const f = fixture();
  const generation = hold();
  const acknowledgement = hold();
  const playback = hold();
  await f.start();
  f.generation(generation.wait);
  f.publication(acknowledgement.wait);
  f.play(playback.wait);
  f.emit("Tôi muốn nói thêm");
  f.advance(1500);
  const turn = f.tick();
  await flush();
  assert.equal(f.mediaStates.at(-1), "WAITING_FOR_REPLY");
  assert.equal(
    f.signals.some((signal) => signal.kind === "PLAYBACK_STARTED"),
    false,
  );
  generation.release();
  await flush();
  assert.equal(f.mediaStates.includes("SPEAKING"), false);
  acknowledgement.release();
  await flush();
  assert.equal(f.mediaStates.at(-1), "SPEAKING");
  f.mute(true);
  playback.release();
  await turn;
  assert.equal(f.mediaStates.at(-1), "MUTED");
  f.connection(false);
  await flush();
  assert.equal(f.mediaStates.at(-1), "DISCONNECTED");
  f.connection(true);
  await flush();
  assert.equal(f.mediaStates.at(-1), "MUTED");
  f.media.state = () => {
    throw new Error("DISPLAY_ONLY_FAILURE");
  };
  f.mute(false);
  await flush();
  assert.equal(f.worker.state, "LISTENING");
  await f.worker.stop("USER_ENDED");
  assert.equal(f.worker.state, "ENDED");
});

void test("late reconnect is rejected even before the periodic deadline check", async () => {
  const f = fixture();
  await f.start();
  f.connection(false);
  f.advance(30001);
  f.connection(true);
  await f.worker.finished;
  assert.equal(f.input(), false);
  assert.equal(f.closes(), 1);
});

void test("native input/interruption failures during stop still cancel work and attempt media teardown", async () => {
  const f = fixture();
  await f.start();
  f.media.input = () => {
    throw new Error("NATIVE_CLOSED");
  };
  f.media.interrupt = () => {
    throw new Error("NATIVE_CLOSED");
  };
  await f.worker.stop("AGENT_FAILURE");
  await f.worker.finished;
  assert.equal(f.closes(), 1);
  assert.equal(f.worker.state, "ENDED");
});
void test("uncertain/oversized/wrong-language final STT closes to text/manual fallback with no fabricated speech", async () => {
  for (const partial of [
    { confidence: 0.3 },
    { confidence: Number.NaN },
    { language: "en" },
  ]) {
    const f = fixture();
    await f.start();
    f.emit("Tôi muốn nói thêm", partial);
    await f.worker.finished;
    assert.equal(f.turns.length, 0);
    assert.equal(f.played.length, 0);
    assert.ok(f.states.includes("TEXT_OR_PUSH_TO_TALK"));
  }
  const f = fixture();
  await f.start();
  f.emit("á".repeat(1000));
  await f.worker.finished;
  assert.equal(f.turns.length, 0);
});
void test("echo cannot call Journal or interrupt; real barge-in cuts playback and uses one publication identity", async () => {
  const f = fixture();
  const gate = hold();
  f.play(gate.wait);
  await f.start();
  f.emit("Tôi đang lo lắng");
  f.advance(1500);
  const speaking = f.tick();
  await flush();
  assert.equal(f.worker.state, "SPEAKING");
  f.emit(f.played[0] ?? "");
  await flush();
  assert.equal(f.turns.length, 1);
  assert.equal(f.signals.filter((s) => s.kind === "INTERRUPTED").length, 0);
  f.emit("Khoan đã", { final: false });
  await flush();
  await speaking;
  assert.equal(f.signals.filter((s) => s.kind === "INTERRUPTED").length, 1);
  const published = f.signals.find((s) => s.kind === "PLAYBACK_STARTED");
  const interrupted = f.signals.find((s) => s.kind === "INTERRUPTED");
  assert.equal(published?.payload.replyId, interrupted?.payload.replyId);
  assert.equal(f.turns.length, 1);
  gate.release();
  await f.worker.stop("USER_ENDED");
});
void test("fresh speech during synthesis cannot cause duplicate generation or resumed audio prefix", async () => {
  const f = fixture();
  const gate = hold();
  f.play(gate.wait);
  await f.start();
  f.emit("Tôi muốn trò chuyện");
  f.advance(1500);
  const work = f.tick();
  await flush();
  f.emit("Tôi muốn hỏi điều khác");
  await flush();
  await work;
  assert.equal(f.turns.length, 1);
  gate.release();
  f.advance(1500);
  await f.tick();
  assert.equal(f.turns.length, 2);
  assert.equal(f.turns[1]?.sequence, 2);
  assert.notEqual(f.turns[0]?.turnId, f.turns[1].turnId);
  await f.worker.stop("USER_ENDED");
});
void test("in-flight Journal work is cancelled by stop, renewed leases never exceed the original hard stop", async () => {
  const f = fixture();
  const gate = hold();
  f.generation(gate.wait);
  await f.start();
  f.emit("Tôi muốn chia sẻ");
  f.advance(1500);
  const work = f.tick();
  await flush();
  f.advance(4000);
  await f.tick();
  assert.equal(f.signals.filter((s) => s.kind === "HEARTBEAT").length, 1);
  await f.worker.stop("USER_ENDED");
  await work;
  gate.release();
  assert.equal(f.played.length, 0);
  assert.equal(f.turns.length, 1);
  assert.equal(f.closes(), 1);
  const g = fixture();
  await g.start();
  g.advance(300001);
  await g.tick();
  assert.equal(g.worker.state, "ENDED");
  assert.equal(g.input(), false);
});
void test("failed renewal, revoked permission and replayed backend controls stop processing and local playback", async () => {
  for (const mode of ["bad", "revoked", "replay"]) {
    const f = fixture();
    await f.start();
    if (mode === "bad") f.badControl();
    else if (mode === "revoked") f.revoke();
    else f.replayControl();
    f.advance(5000);
    await f.tick();
    await f.worker.finished;
    assert.equal(f.input(), false);
    assert.equal(f.closes(), 1);
    assert.equal(f.worker.state, "ENDED");
  }
});
void test("lost lease cannot be refreshed by a local timer; unauthorized claim never starts media", async () => {
  const f = fixture();
  await f.start();
  f.advance(10001);
  await f.tick();
  assert.equal(f.signals.length, 1);
  assert.equal(f.worker.state, "ENDED");
  const g = fixture();
  g.noClaim();
  await g.start();
  assert.equal(g.worker.state, "ENDED");
  assert.equal(g.input(), false);
  assert.equal(g.turns.length, 0);
});
void test("mute clears pending speech and reconnect reauthorizes without creating another session", async () => {
  const f = fixture();
  await f.start();
  f.emit("Tôi muốn chia sẻ");
  f.mute(true);
  await flush();
  f.advance(1500);
  await f.tick();
  assert.equal(f.turns.length, 0);
  assert.equal(f.input(), false);
  f.mute(false);
  await flush();
  f.connection(false);
  await flush();
  assert.equal(f.worker.state, "DISCONNECTED");
  f.advance(5000);
  await f.tick();
  assert.equal(f.signals.at(-1)?.payload.observedState, "RECONNECTING");
  f.connection(true);
  await flush();
  assert.equal(f.worker.state, "LISTENING");
  assert.equal(f.input(), true);
  assert.equal(new Set(f.signals.map((s) => s.voiceSessionId)).size, 1);
  await f.worker.stop("USER_ENDED");
});
void test("provider/network failure cuts off locally and cannot release Journal capacity from worker memory", async () => {
  const f = fixture();
  await f.start();
  f.fail();
  await f.worker.finished;
  assert.equal(f.closes(), 1);
  assert.equal(f.signals.at(-1)?.kind, "FINISHED");
  assert.equal(f.signals.at(-1)?.payload.reason, "PROVIDER_FAILURE");
  assert.equal(
    f.signals.some((s) => Object.hasOwn(s.payload, "billedSeconds")),
    false,
  );
  const g = fixture();
  await g.start();
  g.connection(false);
  await flush();
  for (let i = 0; i < 6; i++) {
    g.advance(5000);
    await g.tick();
  }
  await g.worker.finished;
  assert.equal(g.input(), false);
  assert.equal(g.worker.state, "ENDED");
});
