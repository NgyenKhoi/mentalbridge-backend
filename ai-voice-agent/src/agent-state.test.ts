import assert from "node:assert/strict";
import test from "node:test";
import {
  AgentStatePublisher,
  agentStates,
  isAdmittedAgent,
} from "./agent-state.js";
import type { Dispatch } from "./contract.js";
import type { AgentState } from "./voice-worker.js";

const flush = () => new Promise<void>((resolve) => setImmediate(resolve));
const dispatch: Dispatch = {
  voiceSessionId: "11111111-1111-4111-8111-111111111111",
  attemptId: "22222222-2222-4222-8222-222222222222",
  dispatchId: "33333333-3333-4333-8333-333333333333",
};
const room = `ai-voice-${dispatch.voiceSessionId}`;
void test("only the exact admitted Agent in the expected connected room may publish hints", async () => {
  const current = {
    connected: true,
    room,
    metadata: dispatch.dispatchId,
    identity: `va-${dispatch.attemptId}`,
    agent: true,
  };
  assert.equal(isAdmittedAgent(dispatch, room, current), true);
  for (const patch of [
    { connected: false },
    { room: "consultation-other" },
    { metadata: "stale-dispatch" },
    { identity: "va-previous-attempt" },
    { identity: "vu-owner" },
    { agent: false },
  ]) {
    const writes: object[] = [];
    const publisher = new AgentStatePublisher(
      () => isAdmittedAgent(dispatch, room, { ...current, ...patch }),
      (value) => {
        writes.push(value);
        return Promise.resolve();
      },
    );
    publisher.state("WAITING_FOR_REPLY");
    await publisher.close();
    assert.deepEqual(writes, []);
  }
});

void test("genuine coarse states publish paired fixed keys, then clear terminal hints permanently", async () => {
  const writes: { "lk.agent.state": string; "mb.mam.expression": string }[] =
    [];
  const publisher = new AgentStatePublisher(
    () => true,
    (value) => {
      writes.push(value);
      return Promise.resolve();
    },
  );
  for (const state of [
    "CONNECTING",
    "LISTENING",
    "TRANSCRIBING",
    "WAITING_FOR_REPLY",
    "SPEAKING",
    "INTERRUPTED",
    "MUTED",
    "DISCONNECTED",
    "LISTENING",
  ] as const) {
    publisher.state(state);
    await flush();
  }
  publisher.state("private-transcript" as AgentState);
  await publisher.close();
  publisher.state("SPEAKING");
  await flush();
  assert.deepEqual(
    writes.map((value) => value["lk.agent.state"]),
    [
      "initializing",
      "listening",
      "thinking",
      "speaking",
      "listening",
      "idle",
      "listening",
      "",
    ],
  );
  assert.deepEqual(
    writes.map((value) => value["mb.mam.expression"]),
    ["", "", "", "neutral", "", "", "", ""],
  );
  for (const value of writes) {
    assert.deepEqual(Object.keys(value), [
      "lk.agent.state",
      "mb.mam.expression",
    ]);
    assert.ok(
      value["lk.agent.state"] === "" ||
        agentStates.some((state) => state === value["lk.agent.state"]),
    );
  }
});

void test("in-flight writes are serialized and stale thinking cannot overwrite a later stop", async () => {
  let release: () => void = () => undefined;
  const pending = new Promise<void>((resolve) => {
    release = resolve;
  });
  const writes: { state: string; expression: string }[] = [];
  const publisher = new AgentStatePublisher(
    () => true,
    async (value) => {
      writes.push({
        state: value["lk.agent.state"],
        expression: value["mb.mam.expression"],
      });
      if (writes.length === 1) await pending;
    },
  );
  publisher.state("WAITING_FOR_REPLY");
  await flush();
  publisher.state("SPEAKING", "comfort");
  const closing = publisher.close();
  release();
  await closing;
  assert.deepEqual(writes, [
    { state: "thinking", expression: "" },
    { state: "", expression: "" },
  ]);
});

void test("expression follows first speaking update and clears after interruption or failed write", async () => {
  const writes: { "lk.agent.state": string; "mb.mam.expression": string }[] =
    [];
  let fail = true;
  const publisher = new AgentStatePublisher(
    () => true,
    (value) => {
      writes.push(value);
      if (fail && value["mb.mam.expression"] === "comfort") {
        fail = false;
        return Promise.reject(new Error("DISPLAY_WRITE_FAILED"));
      }
      return Promise.resolve();
    },
  );
  publisher.state("WAITING_FOR_REPLY");
  await flush();
  publisher.state("SPEAKING", "comfort");
  await flush();
  publisher.state("INTERRUPTED");
  await flush();
  await publisher.close();
  assert.deepEqual(
    writes.map((value) => [
      value["lk.agent.state"],
      value["mb.mam.expression"],
    ]),
    [
      ["thinking", ""],
      ["speaking", "comfort"],
      ["", ""],
      ["listening", ""],
      ["", ""],
    ],
  );
});

void test("display publication failures remain isolated and a later transition can recover", async () => {
  let count = 0;
  const publisher = new AgentStatePublisher(
    () => true,
    () => {
      if (++count === 1) throw new Error("PRIVATE_PROVIDER_PAYLOAD");
      return Promise.resolve();
    },
  );
  publisher.state("WAITING_FOR_REPLY");
  await flush();
  publisher.state("WAITING_FOR_REPLY");
  await flush();
  await publisher.close();
  assert.equal(count, 4);
});

void test("terminal clear is written while admitted; a reconnecting room skips it and leaves the reset to the web consumer", async () => {
  for (const reconnecting of [false, true]) {
    const writes: [string, string][] = [];
    let connected = true;
    const publisher = new AgentStatePublisher(
      () =>
        isAdmittedAgent(dispatch, room, {
          connected,
          room,
          metadata: dispatch.dispatchId,
          identity: `va-${dispatch.attemptId}`,
          agent: true,
        }),
      (value) => {
        writes.push([value["lk.agent.state"], value["mb.mam.expression"]]);
        return Promise.resolve();
      },
    );
    publisher.state("SPEAKING", "comfort");
    await flush();
    // LiveKit Room Reconnecting: isConnected is false before the worker stops.
    if (reconnecting) connected = false;
    await publisher.close();
    assert.deepEqual(
      writes,
      reconnecting
        ? [["speaking", "comfort"]]
        : [
            ["speaking", "comfort"],
            ["", ""],
          ],
    );
  }
});
