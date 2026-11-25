import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { channel } from "node:diagnostics_channel";
import test from "node:test";
import {
  VoiceAuthorizationProfile,
  type VoiceAuthorizationDiagnostic,
} from "./voice-authorization-profile.js";
import {
  profileVoiceHttpRead,
  type VoiceHttpDiagnostic,
} from "./voice-http-profile.js";

void test("authorization profiles bind independent rounds to bounded validated metadata without private input", async () => {
  let now = 10;
  const events: VoiceAuthorizationDiagnostic[] = [];
  const input = {
    purpose: "PUBLICATION",
    attemptId: randomUUID(),
    turnId: randomUUID(),
    turnSequence: 2,
  };
  const profile = new VoiceAuthorizationProfile(
    input,
    (value) => events.push(value),
    () => now,
  );
  assert.ok(Object.isFrozen(profile.tags));
  await profile.measure("TOTAL", async () => {
    now = 12;
    return profile.measure("MEDIA_INSPECTION", () => {
      now = 78.9;
      return Promise.resolve(true);
    });
  });
  assert.equal(events.length, 2);
  assert.deepEqual(
    events.map((e) => [e.stage, e.startedMs, e.elapsedMs]),
    [
      ["MEDIA_INSPECTION", 2, 66],
      ["TOTAL", 0, 68],
    ],
  );
  assert.ok(
    events.every(
      (e) =>
        e.authorizationId === profile.tags.authorizationId &&
        e.turnId === input.turnId &&
        e.outcome === "SUCCESS",
    ),
  );
  const invalid = new VoiceAuthorizationProfile(
    { ...input, turnId: "PRIVATE_TRANSCRIPT", key: "PRIVATE_KEY" },
    (value) => events.push(value),
  );
  await invalid.measure("CARE", () => Promise.resolve());
  assert.equal(events.at(-1)?.purpose, "UNSCOPED");
  assert.equal(events.at(-1)?.turnId, undefined);
  assert.notEqual(invalid.tags.authorizationId, profile.tags.authorizationId);
  assert.equal(JSON.stringify(events).includes("PRIVATE"), false);
});

void test("profile failure and observer exceptions preserve the original decision or error", async () => {
  const events: VoiceAuthorizationDiagnostic[] = [];
  const failure = new Error("PRIVATE_ERROR_TOKEN_BODY");
  const profile = new VoiceAuthorizationProfile(
    { purpose: "INITIAL" },
    (value) => {
      events.push(value);
      throw new Error("PRIVATE_OBSERVER");
    },
  );
  await assert.rejects(
    profile.measure("CARE", () => Promise.reject(failure)),
    (e) => e === failure,
  );
  assert.equal(events[0]?.outcome, "FAILED");
  assert.equal(
    await profile.measure("CONSULTATION", () => Promise.resolve(3)),
    3,
  );
  assert.equal(events[1]?.outcome, "SUCCESS");
  assert.equal(JSON.stringify(events).includes("PRIVATE"), false);
  const invalidClock = new VoiceAuthorizationProfile(
    { purpose: "INITIAL" },
    () => assert.fail("non-finite clock emitted"),
    () => Number.NaN,
  );
  assert.equal(
    await invalidClock.measure("TOTAL", () => Promise.resolve(7)),
    7,
  );
});

const publish = (
  event: string,
  request: object,
  extra: Record<string, unknown> = {},
) => {
  channel(
    event === "sendHeaders"
      ? "undici:client:sendHeaders"
      : `undici:request:${event}`,
  ).publish({ request, ...extra });
};
const privateRequest = () =>
  Object.defineProperties(
    {},
    Object.fromEntries(
      ["origin", "path", "headers", "body"].map((name) => [
        name,
        {
          get: () => {
            throw new Error("private request property read");
          },
        },
      ]),
    ),
  );

void test("native request phases use identity without accessing headers, paths, bodies, socket addresses or raw errors", async () => {
  let now = 10;
  const observations: VoiceHttpDiagnostic[] = [];
  const request = privateRequest();
  const socket = {};
  const result = await profileVoiceHttpRead(
    () => {
      now = 15;
      publish("create", request);
      now = 19;
      publish("sendHeaders", request, { socket, headers: "PRIVATE_KEY" });
      now = 120;
      publish("headers", request, {
        response: { statusCode: 200, headers: "PRIVATE_RESPONSE" },
      });
      now = 124;
      publish("trailers", request, { trailers: "PRIVATE_TRAILERS" });
      now = 127;
      return Promise.resolve("unchanged");
    },
    (value) => observations.push(value),
    () => now,
  );
  assert.equal(result, "unchanged");
  assert.deepEqual(observations, [
    {
      nativeRequests: 1,
      httpObservation: "OBSERVED",
      connection: "FIRST_OBSERVED_SOCKET",
      httpStatusClass: "HTTP_2XX",
      httpResult: "COMPLETE",
      sdkBeforeRequestMs: 5,
      requestToSendMs: 4,
      sendToHeadersMs: 101,
      headersToBodyMs: 4,
      sdkAfterBodyMs: 3,
    },
  ]);
  await profileVoiceHttpRead(
    () => {
      publish("create", request);
      publish("sendHeaders", request, { socket });
      publish("trailers", request);
      return Promise.resolve();
    },
    (value) => observations.push(value),
    () => now,
  );
  assert.equal(observations.at(-1)?.connection, "REUSED_OBSERVED_SOCKET");
  assert.equal(JSON.stringify(observations).includes("PRIVATE"), false);
});

void test("interleaved reads retain their own request and unknown phases stay unknown", async () => {
  const events: VoiceHttpDiagnostic[][] = [[], []];
  let resolveFirst: () => void = () => undefined;
  const firstDone = new Promise<void>((resolve) => {
    resolveFirst = resolve;
  });
  const first = privateRequest();
  const second = privateRequest();
  const work = profileVoiceHttpRead(
    async () => {
      publish("create", first);
      await firstDone;
    },
    (e) => events[0]?.push(e),
  );
  await profileVoiceHttpRead(
    () => {
      publish("create", second);
      publish("error", second, { error: new Error("PRIVATE_TIMEOUT") });
      return Promise.resolve();
    },
    (e) => events[1]?.push(e),
  );
  publish("sendHeaders", first, { socket: {} });
  publish("headers", first, { response: { statusCode: 503 } });
  publish("trailers", first);
  resolveFirst();
  await work;
  const firstResult = events[0]?.[0];
  const secondResult = events[1]?.[0];
  assert.ok(firstResult);
  assert.ok(secondResult);
  assert.equal(firstResult.httpStatusClass, "HTTP_5XX");
  assert.equal(firstResult.httpResult, "COMPLETE");
  assert.equal(secondResult.httpResult, "ERROR");
  assert.equal(secondResult.sendToHeadersMs, undefined);
  assert.equal(secondResult.httpStatusClass, "UNKNOWN");
  assert.equal(JSON.stringify(events).includes("PRIVATE"), false);
});

void test("missing or multiple native requests never fabricate a single-request latency or connection", async () => {
  const events: VoiceHttpDiagnostic[] = [];
  await profileVoiceHttpRead(
    () => Promise.resolve(),
    (e) => events.push(e),
  );
  await profileVoiceHttpRead(
    () => {
      publish("create", {});
      publish("create", {});
      return Promise.resolve();
    },
    (e) => events.push(e),
  );
  assert.deepEqual(
    events.map((e) => [
      e.nativeRequests,
      e.httpObservation,
      e.connection,
      e.sendToHeadersMs,
    ]),
    [
      [0, "UNAVAILABLE", "UNKNOWN", undefined],
      [2, "MULTIPLE_REQUESTS", "UNKNOWN", undefined],
    ],
  );
  assert.equal(
    await profileVoiceHttpRead(
      () => Promise.resolve(4),
      () => {
        throw new Error("observer");
      },
    ),
    4,
  );
  const failure = new Error("PRIVATE_FAILURE");
  await assert.rejects(
    profileVoiceHttpRead(
      () => {
        throw failure;
      },
      () => {
        throw new Error("observer");
      },
    ),
    (e) => e === failure,
  );
});
