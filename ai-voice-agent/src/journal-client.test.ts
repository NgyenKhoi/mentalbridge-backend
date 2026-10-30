import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import test from "node:test";
import { AgentFailure, loadConfiguration } from "./configuration.js";
import {
  parseDispatch,
  agentName,
  policyVersion,
  type Signal,
} from "./contract.js";
import { JournalClient } from "./journal-client.js";

const dispatch = {
  voiceSessionId: randomUUID(),
  attemptId: randomUUID(),
  dispatchId: randomUUID(),
};
const configuration = () =>
  loadConfiguration({
    AI_VOICE_JOURNAL_SERVICE_TOKEN:
      "synthetic-worker-service-credential-32-chars",
  });
void test("Journal HTTP timeout/refusal/malformed responses fail closed without retry", async () => {
  let calls = 0;
  let mode = "timeout";
  const server = createServer((_req, response) => {
    calls++;
    if (mode === "timeout") return;
    response.setHeader("content-type", "application/json");
    response.setHeader("cache-control", "no-store");
    response.end(
      mode === "oversize" ? "x".repeat(17000) : "{broken-private-payload",
    );
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert.ok(address && typeof address === "object");
  const client = new JournalClient({
    ...configuration(),
    journalUrl: `http://127.0.0.1:${String(address.port)}`,
  });
  try {
    for (const next of ["timeout", "malformed", "oversize"]) {
      mode = next;
      await assert.rejects(
        client.claim(dispatch),
        (error) =>
          error instanceof AgentFailure &&
          error.message === "JOURNAL_UNAVAILABLE" &&
          error.failure ===
            (next === "timeout" ? "TIMEOUT" : "INVALID_RESPONSE"),
      );
    }
    assert.equal(calls, 3);
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve) => {
      server.close(() => {
        resolve();
      });
    });
  }
  await assert.rejects(client.claim(dispatch), AgentFailure);
  assert.equal(calls, 3);
});
void test("configuration is disabled by default and never leaks unsafe setup values", () => {
  assert.equal(configuration().enabled, false);
  for (const environment of [
    { AI_VOICE_AGENT_ENABLED: "true" },
    { AI_VOICE_JOURNAL_BASE_URL: "http://remote.example" },
    { LIVEKIT_URL: "wss://user:private@project.livekit.cloud" },
    { AI_VOICE_TTS_VOICE_ID: "private" },
    {
      LIVEKIT_API_SECRET: "same-secret-32-characters-synthetic",
      AI_VOICE_JOURNAL_SERVICE_TOKEN: "same-secret-32-characters-synthetic",
    },
  ])
    assert.throws(
      () => loadConfiguration(environment),
      (error) =>
        error instanceof AgentFailure && !error.message.includes("private"),
    );
  const accepted = {
    AI_VOICE_AGENT_ENABLED: "true",
    AI_VOICE_AGENT_APPROVAL_VERSION: "reviewed-qa-v1",
    LIVEKIT_URL: "wss://synthetic.livekit.cloud",
    LIVEKIT_API_KEY: "synthetic-key",
    LIVEKIT_API_SECRET: "synthetic-livekit-secret-32-characters",
    AI_VOICE_JOURNAL_SERVICE_TOKEN: "synthetic-worker-service-token-32-chars",
    AI_VOICE_TTS_VOICE_ID: randomUUID(),
  };
  assert.equal(loadConfiguration(accepted).enabled, true);
  for (const additional of [
    { CI: "true" },
    { NODE_ENV: "test" },
    { AWS_BEARER_TOKEN_BEDROCK: "private" },
    { CARTESIA_API_KEY: "private" },
    { OTEL_EXPORTER_OTLP_ENDPOINT: "https://private" },
  ])
    assert.throws(() => loadConfiguration({ ...accepted, ...additional }));
});

void test("Journal diagnostic failures distinguish HTTP class, invalid body, cancellation and transport without raw payload", async () => {
  for (const [response, expected] of [
    [new Response("PRIVATE", { status: 403 }), "HTTP_4XX"],
    [new Response("PRIVATE", { status: 503 }), "HTTP_5XX"],
    [
      new Response("PRIVATE", {
        headers: {
          "content-type": "application/json",
          "cache-control": "no-store",
        },
      }),
      "INVALID_RESPONSE",
    ],
  ] as const) {
    const client = new JournalClient(configuration(), () =>
      Promise.resolve(response),
    );
    await assert.rejects(
      client.claim(dispatch),
      (error) =>
        error instanceof AgentFailure &&
        error.failure === expected &&
        !JSON.stringify(error).includes("PRIVATE"),
    );
  }
  const cancelled = new AbortController();
  cancelled.abort();
  const client = new JournalClient(configuration(), () =>
    Promise.reject(new Error("PRIVATE")),
  );
  await assert.rejects(
    client.claim(dispatch, cancelled.signal),
    (error) => error instanceof AgentFailure && error.failure === "ABORTED",
  );
  await assert.rejects(
    client.claim(dispatch),
    (error) => error instanceof AgentFailure && error.failure === "TRANSPORT",
  );
});
void test("only exact opaque Journal AI dispatches are accepted", () => {
  const value = {
    agentName,
    metadata: JSON.stringify(dispatch),
    roomName: `ai-voice-${randomUUID()}`,
    roomMetadata: dispatch.dispatchId,
    recording: false,
  };
  assert.deepEqual(parseDispatch(value), dispatch);
  for (const bad of [
    { ...value, agentName: "" },
    { ...value, roomName: `consultation-${randomUUID()}` },
    { ...value, roomMetadata: randomUUID() },
    { ...value, recording: true },
    {
      ...value,
      metadata: JSON.stringify({ ...dispatch, ownerId: randomUUID() }),
    },
  ])
    assert.throws(() => parseDispatch(bad), AgentFailure);
});
void test("Journal consumer authenticates purpose, validates bindings/expiry and does not retry unknown turns", async () => {
  const now = Date.now();
  const leaseId = randomUUID();
  const hardStopAt = new Date(now + 300000).toISOString();
  const validUntil = new Date(now + 10000).toISOString();
  let calls = 0;
  let failure = false;
  let invalid = false;
  let noStore = true;
  const server = createServer((req, res) => {
    void (async () => {
      calls++;
      assert.equal(req.headers.authorization, undefined);
      assert.equal(
        req.headers["x-mentalbridge-voice-worker-token"],
        configuration().journalToken,
      );
      let raw = "";
      for await (const chunk of req) raw += String(chunk);
      const body = JSON.parse(raw) as Record<string, unknown>;
      res.setHeader("content-type", "application/json");
      if (noStore) res.setHeader("cache-control", "no-store");
      if (failure) {
        res.statusCode = 503;
        res.end('{"private":"never expose"}');
        return;
      }
      if (req.url?.endsWith("agent-claim")) {
        assert.equal(body.dispatchId, dispatch.dispatchId);
        res.end(
          JSON.stringify({
            voiceSessionId: invalid ? randomUUID() : dispatch.voiceSessionId,
            attemptId: dispatch.attemptId,
            leaseId,
            validUntil,
            hardStopAt,
            policyVersion,
          }),
        );
        return;
      }
      if (req.url?.endsWith("agent-control")) {
        res.end(
          JSON.stringify({
            action: "CONTINUE",
            control: {
              schemaVersion: 1,
              kind: "LEASE_RENEWED",
              commandId: randomUUID(),
              voiceSessionId: dispatch.voiceSessionId,
              attemptId: dispatch.attemptId,
              sequence: 1,
              issuedAt: new Date(now).toISOString(),
              expiresAt: validUntil,
              payload: {
                leaseId,
                validUntil,
                hardStopAt,
                admittedSeconds: 300,
              },
            },
          }),
        );
        return;
      }
      assert.ok(req.headers["idempotency-key"]);
      assert.equal(body.isFinal, true);
      assert.equal(body.language, "vi-VN");
      res.end(
        JSON.stringify({
          voiceSessionId: dispatch.voiceSessionId,
          turnId: body.turnId,
          replyId: randomUUID(),
          language: "vi-VN",
          spokenText: "Mình đang lắng nghe.",
          expiresAt: validUntil,
          policyVersion,
        }),
      );
    })().catch(() => {
      res.statusCode = 500;
      res.end();
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert.ok(address && typeof address === "object");
  const client = new JournalClient(
    {
      ...configuration(),
      journalUrl: `http://127.0.0.1:${String(address.port)}`,
    },
    fetch,
    () => now,
  );
  try {
    assert.equal((await client.claim(dispatch)).leaseId, leaseId);
    const signal: Signal = {
      schemaVersion: 1,
      kind: "MEDIA_READY",
      commandId: randomUUID(),
      voiceSessionId: dispatch.voiceSessionId,
      attemptId: dispatch.attemptId,
      sequence: 1,
      issuedAt: new Date(now).toISOString(),
      expiresAt: validUntil,
      payload: { leaseId },
    };
    assert.equal((await client.control(dispatch, signal)).action, "CONTINUE");
    const input = {
      attemptId: dispatch.attemptId,
      leaseId,
      turnId: randomUUID(),
      sequence: 1,
      language: "vi-VN" as const,
      isFinal: true as const,
      finalTranscript: "Hôm nay tôi muốn chia sẻ",
    };
    assert.equal(
      (
        await client.turn(
          dispatch,
          input,
          randomUUID(),
          new AbortController().signal,
        )
      ).language,
      "vi-VN",
    );
    invalid = true;
    await assert.rejects(client.claim(dispatch), AgentFailure);
    invalid = false;
    noStore = false;
    await assert.rejects(client.claim(dispatch), AgentFailure);
    noStore = true;
    const before = calls;
    failure = true;
    await assert.rejects(
      client.turn(dispatch, input, randomUUID(), new AbortController().signal),
      (error) =>
        error instanceof AgentFailure && !error.message.includes("private"),
    );
    assert.equal(calls, before + 1);
    const cancelled = new AbortController();
    cancelled.abort();
    await assert.rejects(
      client.claim(dispatch, cancelled.signal),
      AgentFailure,
    );
  } finally {
    await new Promise<void>((resolve, reject) =>
      server.close((error) => {
        if (error) reject(error);
        else resolve();
      }),
    );
  }
});
