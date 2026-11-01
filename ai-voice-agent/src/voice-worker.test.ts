import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import test from "node:test";
import type {
  Control,
  Dispatch,
  Lease,
  Reply,
  Signal,
  StopReason,
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
import {
  agentDiagnostics,
  type AgentDiagnostic,
  type MediaFailure,
} from "./diagnostics.js";
const hold = () => {
  let release: () => void = () => undefined;
  const wait = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { wait, release };
};
const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 10));
function fixture(finalAggregationMs?: number) {
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
  const inputChanges: boolean[] = [];
  let callbacks: MediaCallbacks | undefined;
  let input = false;
  let closes = 0;
  let interrupted = 0;
  let claimed = true;
  let failControl = false;
  let stopControl = false;
  let stopReason: StopReason = "CONSENT_REVOKED";
  let duplicateControl = false;
  let generation: Promise<void> | undefined;
  let play: Promise<void> | undefined;
  let publication: Promise<void> | undefined;
  let readiness: Promise<void> | undefined;
  let heartbeat: Promise<void> | undefined;
  let replyExpiryOffset = 0;
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
              payload: { reason: stopReason },
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
      if (signal.kind === "HEARTBEAT" && heartbeat)
        return heartbeat.then(() => value);
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
        expiresAt: new Date(expires + replyExpiryOffset).toISOString(),
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
      inputChanges.push(value);
    },
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
    finalAggregationMs,
  );
  return {
    worker,
    signals,
    turns,
    played,
    states,
    mediaStates,
    diagnostics,
    inputChanges,
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
    fail: (failure?: MediaFailure) => {
      callbacks?.failed(failure);
    },
    noClaim: () => {
      claimed = false;
    },
    badControl: () => {
      failControl = true;
    },
    revoke: (reason: StopReason = "CONSENT_REVOKED") => {
      stopControl = true;
      stopReason = reason;
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
    heartbeat: (value: Promise<void>) => {
      heartbeat = value;
    },
    replyExpiryOffset: (value: number) => {
      replyExpiryOffset = value;
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

void test("a reply waits for an in-flight heartbeat acknowledgement before validating renewed authority", async () => {
  const f = fixture();
  const generation = hold();
  const acknowledgement = hold();
  await f.start();
  f.generation(generation.wait);
  f.heartbeat(acknowledgement.wait);
  f.emit("Synthetic Vietnamese greeting");
  f.advance(1500);
  const turn = f.tick();
  await flush();
  f.advance(3500);
  const heartbeat = f.tick();
  await flush();
  assert.equal(f.signals.at(-1)?.kind, "HEARTBEAT");
  generation.release();
  await flush();
  assert.equal(f.played.length, 0);
  assert.equal(
    f.diagnostics.some((d) => d.phase === "turn_failed"),
    false,
  );
  acknowledgement.release();
  await Promise.all([turn, heartbeat]);
  assert.equal(f.played.length, 1);
  assert.equal(f.turns.length, 1);
  assert.equal(f.signals.filter((s) => s.kind === "HEARTBEAT").length, 1);
  await f.worker.stop("USER_ENDED");
});

void test("a denied or expired heartbeat acknowledgement cannot authorize pending reply audio", async () => {
  for (const mode of ["denied", "expired"]) {
    const f = fixture();
    const generation = hold();
    const acknowledgement = hold();
    await f.start();
    f.generation(generation.wait);
    f.heartbeat(acknowledgement.wait);
    f.emit("Synthetic Vietnamese greeting");
    f.advance(1500);
    const turn = f.tick();
    await flush();
    if (mode === "denied") f.revoke();
    f.advance(3500);
    const heartbeat = f.tick();
    await flush();
    generation.release();
    await flush();
    assert.equal(f.played.length, 0);
    if (mode === "expired") f.advance(5001);
    acknowledgement.release();
    await Promise.all([turn, heartbeat]);
    await f.worker.finished;
    assert.equal(f.played.length, 0);
    assert.equal(f.turns.length, 1);
    assert.equal(f.input(), false);
    assert.equal(f.worker.state, "ENDED");
  }
});

void test("a reply beyond acknowledged authority is rejected with the exact safe failure category", async () => {
  const f = fixture();
  await f.start();
  f.replyExpiryOffset(1);
  f.emit("PRIVATE_SYNTHETIC_GREETING");
  f.advance(1500);
  await f.tick();
  await f.worker.finished;
  assert.equal(f.played.length, 0);
  assert.equal(
    f.diagnostics.find((d) => d.phase === "turn_failed")?.code,
    "AUTHORITY_EXPIRED",
  );
  assert.equal(JSON.stringify(f.diagnostics).includes("PRIVATE"), false);
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

void test("a shorter configured aggregation interval still waits for adjacent finals and ignores interim speech", async () => {
  const f = fixture(750);
  await f.start();
  f.emit("Tôi muốn chia sẻ");
  f.advance(500);
  await f.tick();
  assert.equal(f.turns.length, 0);
  f.emit("về hôm nay");
  f.advance(749);
  f.emit("câu còn chưa hoàn tất", { final: false });
  await f.tick();
  assert.equal(f.turns.length, 0);
  f.advance(1);
  await f.tick();
  assert.equal(f.turns.length, 1);
  assert.equal(f.turns[0]?.finalTranscript, "Tôi muốn chia sẻ về hôm nay");
  assert.equal(f.played.length, 1);
  await f.worker.stop("USER_ENDED");
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

void test("consecutive turns retain the live input stream while mute and end discard pending speech and stop input", async () => {
  const f = fixture();
  await f.start();
  const initialInputChanges = [...f.inputChanges];
  for (const text of [
    "First synthetic question",
    "Second synthetic question",
  ]) {
    f.emit(text);
    f.advance(1500);
    await f.tick();
    assert.equal(f.input(), true);
    assert.equal(f.worker.state, "LISTENING");
    assert.deepEqual(f.inputChanges, initialInputChanges);
  }
  assert.equal(f.turns.length, 2);
  assert.equal(f.played.length, 2);
  assert.equal(f.closes(), 0);
  f.emit("Discard this pending question");
  f.mute(true);
  await flush();
  assert.equal(f.input(), false);
  f.emit("Ignore speech while muted");
  f.advance(1500);
  await f.tick();
  assert.equal(f.turns.length, 2);
  f.mute(false);
  await flush();
  await f.tick();
  assert.equal(f.input(), true);
  assert.equal(f.turns.length, 2);
  f.emit("Third synthetic question");
  f.advance(1500);
  await f.tick();
  assert.equal(f.turns.length, 3);
  assert.equal(f.played.length, 3);
  f.emit("Discard pending speech on end");
  await f.worker.stop("USER_ENDED");
  f.emit("Ignore speech after end");
  f.advance(1500);
  await f.tick();
  assert.equal(f.input(), false);
  assert.equal(f.closes(), 1);
  assert.equal(f.turns.length, 3);
  assert.equal(f.worker.state, "ENDED");
});

void test("valid late finals queue behind a pending Journal request and become one sequential turn", async () => {
  const f = fixture();
  const generation = hold();
  await f.start();
  f.generation(generation.wait);
  f.emit("PRIVATE_FIRST_QUESTION");
  f.advance(1500);
  const first = f.tick();
  await flush();
  const segmentId = randomUUID();
  f.emit("PRIVATE_LATE_FIRST", { segmentId });
  f.emit("PRIVATE_LATE_FIRST", { segmentId });
  f.advance(500);
  f.emit("PRIVATE_LATE_SECOND");
  f.advance(1500);
  await Promise.all([f.tick(), f.tick()]);
  assert.equal(f.turns.length, 1);
  assert.equal(f.played.length, 0);
  assert.equal(f.worker.state, "WAITING_FOR_REPLY");
  assert.equal(f.closes(), 0);
  assert.equal(
    f.diagnostics.some((value) => value.phase === "agent_stopped"),
    false,
  );
  generation.release();
  await first;
  assert.equal(f.played.length, 1);
  assert.equal(f.worker.state, "TRANSCRIBING");
  await Promise.all([f.tick(), f.tick()]);
  assert.equal(f.turns.length, 2);
  assert.equal(
    f.turns[1]?.finalTranscript,
    "PRIVATE_LATE_FIRST PRIVATE_LATE_SECOND",
  );
  assert.equal(f.turns[1].sequence, 2);
  assert.notEqual(f.turns[1].turnId, f.turns[0]?.turnId);
  assert.equal(f.played.length, 2);
  assert.equal(f.worker.state, "LISTENING");
  assert.equal(JSON.stringify(f.diagnostics).includes("PRIVATE"), false);
  await f.worker.stop("USER_ENDED");
});

void test("queued finals remain byte-bounded and confidence-checked while Journal is busy", async () => {
  for (const mode of ["size", "confidence"] as const) {
    const f = fixture();
    const generation = hold();
    await f.start();
    f.generation(generation.wait);
    f.emit("PRIVATE_FIRST_QUESTION");
    f.advance(1500);
    const first = f.tick();
    await flush();
    if (mode === "size") {
      f.emit("x".repeat(1000));
      f.emit("y".repeat(800));
    } else f.emit("PRIVATE_INVALID_CONFIDENCE", { confidence: Number.NaN });
    await f.worker.finished;
    await first;
    generation.release();
    f.advance(1500);
    await f.tick();
    assert.equal(f.turns.length, 1);
    assert.equal(f.played.length, 0);
    assert.equal(f.input(), false);
    assert.equal(
      f.diagnostics.find((value) => value.transcriptResult === "REJECTED")
        ?.rejection,
      mode === "size" ? "PENDING_TOO_LARGE" : "INVALID_CONFIDENCE",
    );
    assert.equal(JSON.stringify(f.diagnostics).includes("PRIVATE"), false);
  }
});

void test("failed or cancelled Journal work discards queued finals without another provider request", async () => {
  for (const mode of ["failure", "owner-end", "authority"] as const) {
    const f = fixture();
    let reject: (reason: Error) => void = () => undefined;
    const generation = new Promise<void>((_resolve, fail) => {
      reject = fail;
    });
    await f.start();
    f.generation(generation);
    f.emit("PRIVATE_FIRST_QUESTION");
    f.advance(1500);
    const first = f.tick();
    await flush();
    f.emit("PRIVATE_QUEUED_QUESTION");
    if (mode === "failure") reject(new Error("PRIVATE_PROVIDER_FAILURE"));
    else if (mode === "owner-end") await f.worker.stop("USER_ENDED");
    else {
      f.revoke();
      f.advance(3500);
      await f.tick();
    }
    await first;
    await f.worker.finished;
    f.advance(1500);
    f.emit("PRIVATE_IGNORED_AFTER_END");
    await f.tick();
    assert.equal(f.turns.length, 1);
    assert.equal(f.played.length, 0);
    assert.equal(f.closes(), 1);
    assert.equal(f.worker.state, "ENDED");
    assert.equal(JSON.stringify(f.diagnostics).includes("PRIVATE"), false);
  }
});

void test("a prior playback interruption cannot hide a failed next Journal request or replay its queued speech", async () => {
  const f = fixture();
  const playback = hold();
  await f.start();
  f.play(playback.wait);
  f.emit("PRIVATE_FIRST_QUESTION");
  f.advance(1500);
  const first = f.tick();
  await flush();
  f.emit("PRIVATE_SECOND_QUESTION");
  await first;
  playback.release();
  let reject: (reason: Error) => void = () => undefined;
  f.generation(
    new Promise<void>((_resolve, fail) => {
      reject = fail;
    }),
  );
  f.advance(1500);
  const second = f.tick();
  await flush();
  f.emit("PRIVATE_QUEUED_AFTER_INTERRUPTION");
  reject(new Error("PRIVATE_PROVIDER_FAILURE"));
  await second;
  await f.worker.finished;
  f.advance(1500);
  await f.tick();
  assert.equal(f.turns.length, 2);
  assert.equal(f.played.length, 1);
  assert.equal(f.worker.state, "ENDED");
  assert.equal(
    f.diagnostics.findLast((value) => value.phase === "agent_stopped")?.reason,
    "PROVIDER_FAILURE",
  );
  assert.equal(JSON.stringify(f.diagnostics).includes("PRIVATE"), false);
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
void test("invalid/oversized/wrong-language final STT closes to text/manual fallback with no fabricated speech", async () => {
  for (const partial of [
    { confidence: 0.3, language: "en" },
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
void test("low-confidence finals discard unsubmitted prefixes without generating or ending the call", async () => {
  const f = fixture();
  await f.start();
  f.emit("PRIVATE_UNSUBMITTED_PREFIX");
  assert.equal(f.worker.state, "TRANSCRIBING");
  f.emit("PRIVATE_UNCERTAIN_FINAL", { confidence: 0.46875 });
  assert.equal(f.worker.state, "LISTENING");
  f.advance(1600);
  await f.tick();
  assert.equal(f.turns.length, 0);
  assert.equal(f.played.length, 0);
  assert.equal(f.closes(), 0);
  assert.equal(f.input(), true);
  assert.deepEqual(
    f.diagnostics.find((value) => value.transcriptResult === "DISCARDED"),
    {
      phase: "stt_final",
      transcriptResult: "DISCARDED",
      rejection: "LOW_CONFIDENCE",
      confidenceValid: true,
      confidence: 0.46875,
      languageAccepted: true,
    },
  );
  assert.equal(JSON.stringify(f.diagnostics).includes("PRIVATE"), false);
  f.emit("PRIVATE_FRESH_VALID_QUESTION", { confidence: 0.8 });
  f.advance(1500);
  await f.tick();
  assert.equal(f.turns.length, 1);
  assert.equal(f.turns[0]?.finalTranscript, "PRIVATE_FRESH_VALID_QUESTION");
  await f.worker.stop("USER_ENDED");
});

void test("a low-confidence final during approved playback does not interrupt audio or issue another turn", async () => {
  const f = fixture();
  const playback = hold();
  f.play(playback.wait);
  await f.start();
  f.emit("PRIVATE_APPROVED_QUESTION");
  f.advance(1500);
  const first = f.tick();
  await flush();
  assert.equal(f.worker.state, "SPEAKING");
  f.emit("PRIVATE_UNCERTAIN_DURING_PLAYBACK", { confidence: 0.46875 });
  await flush();
  assert.equal(f.worker.state, "SPEAKING");
  assert.equal(f.interrupted(), 0);
  assert.equal(f.closes(), 0);
  assert.equal(f.turns.length, 1);
  assert.equal(f.played.length, 1);
  assert.equal(
    f.signals.some((value) => value.kind === "INTERRUPTED"),
    false,
  );
  assert.equal(
    f.diagnostics.some((value) => value.phase === "agent_stopped"),
    false,
  );
  playback.release();
  await first;
  f.emit("PRIVATE_NEW_VALID_UTTERANCE");
  f.advance(1500);
  await f.tick();
  assert.equal(f.turns.length, 2);
  assert.equal(f.played.length, 2);
  assert.equal(JSON.stringify(f.diagnostics).includes("PRIVATE"), false);
  await f.worker.stop("USER_ENDED");
});

void test("discarding an uncertain queued utterance does not cancel an authorized Journal request", async () => {
  const f = fixture();
  const generation = hold();
  f.generation(generation.wait);
  await f.start();
  f.emit("PRIVATE_IN_FLIGHT_QUESTION");
  f.advance(1500);
  const first = f.tick();
  await flush();
  f.emit("PRIVATE_QUEUED_PREFIX");
  f.emit("PRIVATE_UNCERTAIN_QUEUED_FINAL", { confidence: 0 });
  assert.equal(f.worker.state, "WAITING_FOR_REPLY");
  assert.equal(f.turns.length, 1);
  assert.equal(f.closes(), 0);
  generation.release();
  await first;
  f.advance(1500);
  await f.tick();
  assert.equal(f.turns.length, 1);
  assert.equal(f.played.length, 1);
  assert.equal(f.worker.state, "LISTENING");
  await f.worker.stop("USER_ENDED");
});

void test("discarded speech cannot reset owner idle enforcement or extend the session hard stop", async () => {
  const idle = fixture();
  await idle.start();
  idle.emit("PRIVATE_UNCERTAIN_NO_ACTIVITY", { confidence: 0.2 });
  idle.revoke("IDLE_TIMEOUT");
  idle.advance(5000);
  await idle.tick();
  await idle.worker.finished;
  assert.equal(idle.turns.length, 0);
  assert.ok(
    idle.diagnostics.some(
      (value) =>
        value.phase === "agent_stopped" && value.reason === "IDLE_TIMEOUT",
    ),
  );
  const deadline = fixture();
  await deadline.start();
  for (let step = 0; step < 60; step++) {
    deadline.advance(5000);
    deadline.emit("PRIVATE_UNCERTAIN_NO_EXTENSION", { confidence: 0.2 });
    await deadline.tick();
  }
  await deadline.worker.finished;
  assert.equal(deadline.turns.length, 0);
  assert.equal(deadline.played.length, 0);
  assert.equal(deadline.worker.state, "ENDED");
  assert.equal(deadline.closes(), 1);
  assert.equal(deadline.input(), false);
});

void test("low confidence never masks invalid language, oversized input or invalid confidence failures", async () => {
  for (const [text, partial, reason] of [
    [
      "PRIVATE_WRONG_LANGUAGE",
      { confidence: 0.2, language: "en" },
      "LANGUAGE_MISMATCH",
    ],
    ["x".repeat(2001), { confidence: 0.2 }, "TRANSCRIPT_TOO_LARGE"],
    ["x".repeat(1801), { confidence: 0.2 }, "PENDING_TOO_LARGE"],
    ["PRIVATE_NONFINITE", { confidence: Number.NaN }, "INVALID_CONFIDENCE"],
  ] as const) {
    const f = fixture();
    await f.start();
    f.emit(text, partial);
    await f.worker.finished;
    assert.equal(f.turns.length, 0);
    assert.equal(f.played.length, 0);
    assert.equal(
      f.diagnostics.find((value) => value.phase === "stt_final")?.rejection,
      reason,
    );
    assert.equal(
      f.diagnostics.some((value) => value.transcriptResult === "DISCARDED"),
      false,
    );
    assert.equal(JSON.stringify(f.diagnostics).includes("PRIVATE"), false);
  }
});

void test("final rejection diagnostics identify confidence, language and size without transcript", async () => {
  for (const [partial, expected] of [
    [{ confidence: 0.79, language: "en" }, "LANGUAGE_MISMATCH"],
    [{ confidence: Number.NaN }, "INVALID_CONFIDENCE"],
    [{ confidence: Number.POSITIVE_INFINITY }, "INVALID_CONFIDENCE"],
    [{ confidence: Number.NEGATIVE_INFINITY }, "INVALID_CONFIDENCE"],
    [{ confidence: -0.1 }, "INVALID_CONFIDENCE"],
    [{ confidence: 1.1 }, "INVALID_CONFIDENCE"],
    [{ language: "en" }, "LANGUAGE_MISMATCH"],
    [{ language: "PRIVATE_LANGUAGE" }, "LANGUAGE_MISMATCH"],
  ] as const) {
    const f = fixture();
    await f.start();
    f.emit("PRIVATE_SYNTHETIC_TEXT", partial);
    await f.worker.finished;
    const rejected = f.diagnostics.find((d) => d.phase === "stt_final");
    assert.ok(rejected);
    assert.equal(rejected.rejection, expected);
    assert.equal(rejected.transcriptResult, "REJECTED");
    assert.equal(f.turns.length, 0);
    assert.equal(JSON.stringify(f.diagnostics).includes("PRIVATE"), false);
    if (expected === "INVALID_CONFIDENCE")
      assert.equal(Object.hasOwn(rejected, "confidence"), false);
  }
  const f = fixture();
  await f.start();
  f.emit("x".repeat(2001));
  await f.worker.finished;
  assert.equal(
    f.diagnostics.find((d) => d.phase === "stt_final")?.rejection,
    "TRANSCRIPT_TOO_LARGE",
  );
});
void test("empty final is distinguished from a rejected final and keeps the worker listening", async () => {
  const f = fixture();
  await f.start();
  f.emit("   ");
  assert.ok(
    f.diagnostics.some(
      (d) => d.phase === "stt_final" && d.transcriptResult === "EMPTY",
    ),
  );
  assert.equal(
    f.diagnostics.some((d) => d.phase === "agent_stopped"),
    false,
  );
  f.emit("Synthetic greeting", { confidence: 0.8 });
  f.advance(1500);
  await f.tick();
  assert.equal(f.turns.length, 1);
  await f.worker.stop("USER_ENDED");
});
void test("media failures retain their safe source and shutdown does not replace the initial cause", async () => {
  const f = fixture();
  await f.start();
  f.fail({
    source: "SDK_ERROR",
    component: "STT",
    code: "HTTP_4XX",
    recoverable: false,
  });
  await f.worker.finished;
  f.fail({ source: "SDK_CLOSE" });
  assert.deepEqual(
    f.diagnostics.filter((d) => d.phase === "media_failed"),
    [
      {
        phase: "media_failed",
        source: "SDK_ERROR",
        component: "STT",
        code: "HTTP_4XX",
        recoverable: false,
      },
    ],
  );
  assert.equal(f.turns.length, 0);
  assert.equal(
    f.diagnostics.find((d) => d.phase === "agent_stopped")?.reason,
    "PROVIDER_FAILURE",
  );
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
