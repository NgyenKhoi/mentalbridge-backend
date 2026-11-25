import assert from "node:assert/strict";
import test from "node:test";
import { randomUUID } from "node:crypto";
import { crc32 } from "node:zlib";
import { createServer } from "node:http";
import { once } from "node:events";
import { readBedrockConverseStream } from "../llm-providers/bedrock-converse-stream.js";
import { VoiceJsonObjectArray } from "../llm-providers/voice-json-array.js";
import {
  VoiceProviderFailure,
  type VoiceProviderObservation,
} from "../llm-providers/voice-diagnostics.js";
import {
  RoutedChatProvider,
  providerOutputJsonSchema,
  voiceProviderOutputJsonSchema,
  selectCompanionProviderRoute,
  validatedAssistantMessage,
  type ChatRoute,
} from "../companion-chat/companion-chat.js";
import { loadConfiguration } from "../configuration/configuration.js";

const configuration = loadConfiguration({
  NODE_ENV: "test",
  IDENTITY_JWT_ISSUER: "https://identity.test",
  IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
  IDENTITY_JWT_KEY_ID: "test",
  IDENTITY_JWT_PUBLIC_KEY: "synthetic-public-key",
});
const route: ChatRoute = {
  workload: "COMPANION_CHAT",
  servicePlan: "PLUS",
  entitlementSource: "PAID",
  entitlementPolicyVersion: "service-entitlement-v1",
  entitlementVersion: 1,
  ...selectCompanionProviderRoute(configuration, "PLUS"),
  provider: "BEDROCK",
  model: "global.anthropic.claude-sonnet-4-6",
};

// MB-846d: every Voice request counts its exact input first (1,900-token cap).
const counting = (input: string | URL | Request) =>
  new URL(input instanceof Request ? input.url : input).pathname.endsWith(
    "/count-tokens",
  );
const counted = () => Promise.resolve(Response.json({ inputTokens: 100 }));

const streamMessage = {
  message: "Mình đang lắng nghe bạn. Bạn muốn bắt đầu từ điều gì?",
  language: "vi",
  authority: "SUPPORT_ONLY",
  clinicalAssessment: "NONE",
  safetyDecision: "NONE",
  eligibilityDecision: "NONE",
  businessAction: "NONE",
  thirdPartyAction: "NONE",
};
void test("speech-unit JSON framing rejects incomplete, oversized, excessive and malformed arrays", () => {
  for (const value of [
    "[]",
    "{}",
    '[{"x":1},]',
    '[{"x":1}]x',
    '[{"x":1}',
    '[{"x":1},{"x":2},{"x":3}]',
    "[true]",
    `[${"[".repeat(18)}]`,
  ]) {
    const parser = new VoiceJsonObjectArray(() => undefined);
    assert.throws(() => {
      parser.accept(value);
      parser.finish();
    }, VoiceProviderFailure);
  }
  assert.throws(() => {
    new VoiceJsonObjectArray(() => undefined, 2, 5).accept('[{"x":1}]');
  }, VoiceProviderFailure);
  const values: unknown[] = [];
  const parser = new VoiceJsonObjectArray((value) => values.push(value));
  const input = JSON.stringify([
    { message: 'Tiếng Việt "rõ" [đủ].' },
    { message: "Đã xong!" },
  ]);
  for (const character of input) parser.accept(character);
  parser.finish();
  assert.equal(values.length, 2);
});

void test("opt-in speech units are fully schema/safety validated before model completion while usage stays once-only", async () => {
  const original = globalThis.fetch;
  const first = { ...streamMessage, message: "Mình đang lắng nghe bạn." };
  const second = {
    ...streamMessage,
    message: "Bạn muốn chia sẻ thêm điều gì?",
  };
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  let calls = 0;
  let resolved = false;
  const observations: VoiceProviderObservation[] = [];
  const units: string[] = [];
  globalThis.fetch = (url, init) => {
    calls++;
    if (
      new URL(url instanceof Request ? url.url : url).pathname.endsWith(
        "/count-tokens",
      )
    ) {
      const system = (
        JSON.parse(init?.body as string) as {
          input: { converse: { system: { text: string }[] } };
        }
      ).input.converse.system[0]?.text;
      assert.ok(system);
      // MB-846d: the full bounded system (guidance, unit rules, schema) must leave room under 1,900 tokens.
      assert.ok(Buffer.byteLength(system, "utf8") <= 4_800);
      return Promise.resolve(Response.json({ inputTokens: 100 }));
    }
    assert.equal(typeof init?.body, "string");
    assert.equal(
      (
        JSON.parse(init?.body as string) as {
          inferenceConfig: { maxTokens: number };
        }
      ).inferenceConfig.maxTokens,
      600,
    );
    return Promise.resolve(
      new Response(
        new ReadableStream<Uint8Array>({
          start(value) {
            controller = value;
            value.enqueue(eventFrame("messageStart", { role: "assistant" }));
            value.enqueue(
              eventFrame("contentBlockDelta", {
                contentBlockIndex: 0,
                delta: { text: `[${JSON.stringify(first)},` },
              }),
            );
          },
        }),
        { headers: { "content-type": "application/vnd.amazon.eventstream" } },
      ),
    );
  };
  const provider = new RoutedChatProvider({
    ...configuration,
    BEDROCK_API_KEY: "PRIVATE_KEY",
    VOICE_PILOT: {
      startsAt: new Date(),
      endsAt: new Date(Date.now() + 60000),
      ownerIds: [randomUUID()],
      measurement: {
        campaignId: randomUUID(),
        maxSessions: 2,
        maxConcurrentSessions: 1,
        maxTurns: 12,
        maxVoiceSeconds: 480,
        maxTtsCharacters: 4000,
        voiceId: "8e8f222d-c817-4cc5-822b-8bf76ca7e98d",
      },
    },
  });
  try {
    const work = provider
      .reply("PRIVATE_INPUT", { kinds: [], prompt: "PRIVATE_CONTEXT" }, route, {
        voice: true,
        voiceObserver: (value) => observations.push(value),
        onSpeechUnit: (value) => units.push(value.message),
      })
      .then((value) => {
        resolved = true;
        return value;
      });
    await new Promise((resolve) => setTimeout(resolve, 20));
    assert.deepEqual(units, [first.message]);
    assert.equal(resolved, false);
    assert(
      !observations.some(
        (value) => value.phase === "bedrock_stream_message_stopped",
      ),
    );
    controller.enqueue(
      eventFrame("contentBlockDelta", {
        contentBlockIndex: 0,
        delta: { text: `${JSON.stringify(second)}]` },
      }),
    );
    controller.enqueue(
      eventFrame("contentBlockStop", { contentBlockIndex: 0 }),
    );
    controller.enqueue(eventFrame("messageStop", { stopReason: "end_turn" }));
    controller.enqueue(
      eventFrame("metadata", { usage: { inputTokens: 100, outputTokens: 60 } }),
    );
    controller.close();
    assert.equal((await work).message, `${first.message} ${second.message}`);
    assert.equal(calls, 2);
    assert.equal(
      observations.filter((value) => value.phase === "provider_usage").length,
      1,
    );
    assert.doesNotMatch(JSON.stringify(observations), /PRIVATE|lắng|chia sẻ/u);
  } finally {
    globalThis.fetch = original;
  }
});

void test("speech-unit mode rejects unsafe/schema/prefix and abnormal outer completions without retry or fallback", async () => {
  const original = globalThis.fetch;
  const pilot = {
    startsAt: new Date(),
    endsAt: new Date(Date.now() + 60000),
    ownerIds: [randomUUID()],
    measurement: {
      campaignId: randomUUID(),
      maxSessions: 2,
      maxConcurrentSessions: 1 as const,
      maxTurns: 12,
      maxVoiceSeconds: 480,
      maxTtsCharacters: 4000,
      voiceId: "8e8f222d-c817-4cc5-822b-8bf76ca7e98d" as const,
    },
  };
  try {
    for (const item of [
      {
        output: [{ ...streamMessage, authority: "PRIVATE" }],
        stop: "end_turn",
        units: 0,
      },
      {
        output: [
          { ...streamMessage, message: "Tôi chẩn đoán bạn bị trầm cảm." },
        ],
        stop: "end_turn",
        units: 0,
      },
      {
        output: [{ ...streamMessage, message: "Mình đang lắng nghe" }],
        stop: "end_turn",
        units: 0,
      },
      { output: [streamMessage], stop: "stop_sequence", units: 1 },
      { output: [streamMessage], stop: undefined, units: 1 },
    ]) {
      let calls = 0,
        units = 0;
      const observed: VoiceProviderObservation[] = [];
      globalThis.fetch = (url) => {
        calls++;
        if (
          new URL(url instanceof Request ? url.url : url).pathname.endsWith(
            "/count-tokens",
          )
        )
          return Promise.resolve(Response.json({ inputTokens: 100 }));
        const frames = streamFrames(JSON.stringify(item.output));
        frames[3] = eventFrame("messageStop", { stopReason: item.stop });
        return Promise.resolve(streamResponse(frames));
      };
      const provider = new RoutedChatProvider({
        ...configuration,
        BEDROCK_API_KEY: "PRIVATE_KEY",
        VOICE_PILOT: pilot,
      });
      await assert.rejects(
        provider.reply("PRIVATE_INPUT", { kinds: [], prompt: "" }, route, {
          voice: true,
          onSpeechUnit: () => {
            units++;
          },
          voiceObserver: (value) => observed.push(value),
        }),
        VoiceProviderFailure,
      );
      assert.equal(units, item.units);
      assert.equal(calls, 2);
      assert(
        observed.filter((value) => value.phase === "provider_usage").length <=
          1,
      );
      assert.doesNotMatch(JSON.stringify(observed), /PRIVATE|trầm|lắng/u);
    }
  } finally {
    globalThis.fetch = original;
  }
});
function eventFrame(event: string, body: unknown, kind = "event") {
  const header = (name: string, value: string) => {
    const prefix = Buffer.from([
      Buffer.byteLength(name),
      ...Buffer.from(name),
      7,
      0,
      0,
    ]);
    prefix.writeUInt16BE(Buffer.byteLength(value), prefix.length - 2);
    return Buffer.concat([prefix, Buffer.from(value)]);
  };
  const headers = Buffer.concat([
    header(":message-type", kind),
    header(":event-type", event),
    header(":content-type", "application/json"),
  ]);
  const payload = Buffer.from(JSON.stringify(body));
  const frame = Buffer.alloc(headers.length + payload.length + 16);
  frame.writeUInt32BE(frame.length, 0);
  frame.writeUInt32BE(headers.length, 4);
  frame.writeUInt32BE(crc32(frame.subarray(0, 8)), 8);
  headers.copy(frame, 12);
  payload.copy(frame, 12 + headers.length);
  frame.writeUInt32BE(
    crc32(frame.subarray(0, frame.length - 4)),
    frame.length - 4,
  );
  return frame;
}
function streamFrames(
  text = JSON.stringify(streamMessage),
  stopReason: string | undefined = "end_turn",
): [Buffer, Buffer, Buffer, Buffer, Buffer] {
  return [
    eventFrame("messageStart", { role: "assistant" }),
    eventFrame("contentBlockDelta", { contentBlockIndex: 0, delta: { text } }),
    eventFrame("contentBlockStop", { contentBlockIndex: 0 }),
    eventFrame("messageStop", { stopReason }),
    eventFrame("metadata", {
      usage: { inputTokens: 100, outputTokens: 70, totalTokens: 170 },
    }),
  ];
}
const streamResponse = (chunks: Uint8Array[]) =>
  new Response(
    new ReadableStream({
      start(controller) {
        for (const chunk of chunks) controller.enqueue(chunk);
        controller.close();
      },
    }),
    { headers: { "content-type": "application/vnd.amazon.eventstream" } },
  );

void test("ConverseStream verifies wire CRCs across byte-split UTF8 and buffers until complete", async () => {
  const bytes = Buffer.concat(streamFrames());
  const chunks = [...bytes].map((byte) => Uint8Array.of(byte));
  const observations: VoiceProviderObservation[] = [];
  const result = await readBedrockConverseStream(
    streamResponse(chunks),
    AbortSignal.timeout(2000),
    (value) => observations.push(value),
  );
  assert.deepEqual(result, {
    output: { message: { content: [{ text: JSON.stringify(streamMessage) }] } },
    stopReason: "end_turn",
    usage: { inputTokens: 100, outputTokens: 70 },
  });
  assert.deepEqual(
    observations.map((value) => value.phase),
    [
      "bedrock_stream_first_text",
      "bedrock_stream_message_stopped",
      "provider_usage",
      "bedrock_stream_completed",
    ],
  );
  assert.doesNotMatch(
    JSON.stringify(observations),
    /lắng nghe|delta|authorization|"text"|"body"/i,
  );
});

void test("ConverseStream rejects corrupt, truncated, duplicate and out-of-order frames with closed diagnostics", async () => {
  const frames = streamFrames();
  const corrupt = Buffer.from(frames[1]);
  corrupt[corrupt.length - 1] = (corrupt[corrupt.length - 1] ?? 0) ^ 1;
  const corruptPrelude = Buffer.from(frames[0]);
  corruptPrelude[8] = (corruptPrelude[8] ?? 0) ^ 1;
  const oversized = Buffer.from(frames[0]);
  oversized.writeUInt32BE(1024 * 1024 + 1, 0);
  oversized.writeUInt32BE(crc32(oversized.subarray(0, 8)), 8);
  const cases = [
    [corruptPrelude],
    [oversized],
    [frames[0], corrupt],
    [Buffer.concat(frames).subarray(0, Buffer.concat(frames).length - 1)],
    frames.slice(0, -1),
    [frames[1], ...frames],
    [frames[0], ...frames],
    [...frames, frames[4]],
    [
      frames[0],
      eventFrame("contentBlockDelta", {
        contentBlockIndex: 0,
        delta: { toolUse: { input: "PRIVATE" } },
      }),
    ],
    [
      frames[0],
      eventFrame("contentBlockStart", {
        contentBlockIndex: 0,
        start: { toolUse: { name: "PRIVATE" } },
      }),
    ],
    [frames[0], eventFrame("unknown", { private: "PRIVATE" })],
    [
      frames[0],
      eventFrame("contentBlockDelta", {
        contentBlockIndex: 0,
        delta: { text: "x".repeat(65537) },
      }),
    ],
    [frames[0], frames[1], frames[3], frames[4]],
    [frames[0], frames[1], frames[2], frames[1]],
  ];
  for (const candidate of cases) {
    const seen: VoiceProviderObservation[] = [];
    await assert.rejects(
      readBedrockConverseStream(
        streamResponse(candidate),
        AbortSignal.timeout(2000),
        (value) => seen.push(value),
      ),
      (error: unknown) =>
        error instanceof VoiceProviderFailure &&
        error.code === "STREAM_PROTOCOL_INVALID" &&
        !error.message.includes("PRIVATE"),
    );
    assert.ok(
      seen.filter((value) => value.phase === "provider_usage").length <= 1,
    );
    assert.doesNotMatch(JSON.stringify(seen), /PRIVATE/);
  }
  await assert.rejects(
    readBedrockConverseStream(
      Response.json({ private: "PRIVATE" }),
      AbortSignal.timeout(1000),
    ),
    (error: unknown) =>
      error instanceof VoiceProviderFailure &&
      error.code === "STREAM_PROTOCOL_INVALID",
  );
  let rejectedBodyCancelled = 0;
  const rejectedBody = new ReadableStream<Uint8Array>({
    cancel() {
      rejectedBodyCancelled++;
    },
  });
  await assert.rejects(
    readBedrockConverseStream(
      new Response(rejectedBody, { headers: { "content-type": "text/plain" } }),
      AbortSignal.timeout(1000),
    ),
  );
  assert.equal(rejectedBodyCancelled, 1);
});

void test("ConverseStream cancellation and deadline cancel a stalled body with no late completion", async () => {
  for (const timeout of [false, true]) {
    let cancelled = 0;
    const observations: VoiceProviderObservation[] = [];
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(streamFrames()[0]);
      },
      cancel() {
        cancelled++;
      },
    });
    const controller = new AbortController();
    const timer = setTimeout(() => {
      controller.abort(
        timeout
          ? new DOMException("PRIVATE", "TimeoutError")
          : new Error("PRIVATE"),
      );
    }, 10);
    try {
      await assert.rejects(
        readBedrockConverseStream(
          new Response(body, {
            headers: { "content-type": "application/vnd.amazon.eventstream" },
          }),
          controller.signal,
          (value) => observations.push(value),
        ),
        (error: unknown) =>
          error instanceof VoiceProviderFailure &&
          error.code === (timeout ? "TIMEOUT" : "ABORTED"),
      );
      assert.equal(cancelled, 1);
      assert.deepEqual(observations, []);
    } finally {
      clearTimeout(timer);
    }
  }
});

void test("ConverseStream provider exceptions remain closed and do not expose the error payload", async () => {
  await assert.rejects(
    readBedrockConverseStream(
      streamResponse([
        eventFrame(
          "modelStreamErrorException",
          { message: "PRIVATE_PROVIDER_ERROR", originalStatusCode: 500 },
          "exception",
        ),
      ]),
      AbortSignal.timeout(1000),
    ),
    (error: unknown) =>
      error instanceof VoiceProviderFailure &&
      error.code === "STREAM_PROVIDER_EXCEPTION" &&
      error.message === "STREAM_PROVIDER_EXCEPTION",
  );
});

// MB-846d: speech units are the only Voice ConverseStream path (tests above); single-object Voice streaming was removed with the flags.
void test("Text and plain Voice use Converse; plain Voice is counted and schema-in-prompt", async () => {
  const original = globalThis.fetch;
  try {
    for (const voice of [false, true]) {
      const paths: string[] = [];
      globalThis.fetch = (url, init) => {
        const path = new URL(url instanceof Request ? url.url : url).pathname;
        paths.push(path);
        if (path.endsWith("/count-tokens"))
          return Promise.resolve(Response.json({ inputTokens: 100 }));
        assert.ok(path.endsWith("/converse"));
        assert.equal(init?.redirect, undefined);
        assert.ok(init);
        assert.ok(typeof init.body === "string");
        const request = JSON.parse(init.body) as { outputConfig?: unknown };
        assert.equal(Boolean(request.outputConfig), !voice);
        return Promise.resolve(
          Response.json({
            stopReason: "end_turn",
            output: {
              message: { content: [{ text: JSON.stringify(streamMessage) }] },
            },
            usage: { inputTokens: 100, outputTokens: 70 },
          }),
        );
      };
      const result = await new RoutedChatProvider({
        ...configuration,
        BEDROCK_API_KEY: "PRIVATE",
      }).reply("PRIVATE", { kinds: [], prompt: "" }, route, { voice });
      assert.equal(result.message, streamMessage.message);
      assert.equal(paths.length, voice ? 2 : 1);
    }
  } finally {
    globalThis.fetch = original;
  }
});

void test("stream usage is observed once even if later protocol evidence fails; observer failures preserve results", async () => {
  const seen: VoiceProviderObservation[] = [];
  await assert.rejects(
    readBedrockConverseStream(
      streamResponse([
        ...streamFrames(),
        eventFrame("unknown", { message: "PRIVATE" }),
      ]),
      AbortSignal.timeout(1000),
      (value) => seen.push(value),
    ),
    (error: unknown) =>
      error instanceof VoiceProviderFailure &&
      error.code === "STREAM_PROTOCOL_INVALID",
  );
  assert.equal(
    seen.filter((value) => value.phase === "provider_usage").length,
    1,
  );
  assert.doesNotMatch(JSON.stringify(seen), /PRIVATE|lắng nghe/);
  await readBedrockConverseStream(
    streamResponse(streamFrames()),
    AbortSignal.timeout(1000),
    () => {
      throw new Error("PRIVATE_OBSERVER");
    },
  );
  const broken = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.error(new Error("PRIVATE_NETWORK"));
    },
  });
  await assert.rejects(
    readBedrockConverseStream(
      new Response(broken, {
        headers: { "content-type": "application/vnd.amazon.eventstream" },
      }),
      AbortSignal.timeout(1000),
    ),
    (error: unknown) =>
      error instanceof VoiceProviderFailure &&
      error.code === "TRANSPORT" &&
      error.message === "TRANSPORT",
  );
});

void test("native HTTP eventstream integration observes first delta before EOF without releasing unvalidated output", async () => {
  let release: () => void = () => undefined;
  const finish = new Promise<void>((resolve) => {
    release = resolve;
  });
  let markFirst: () => void = () => undefined;
  const first = new Promise<void>((resolve) => {
    markFirst = resolve;
  });
  const frames = streamFrames();
  const server = createServer((_request, response) => {
    response.writeHead(200, {
      "content-type": "application/vnd.amazon.eventstream",
    });
    response.write(frames[0]);
    response.write(frames[1]);
    void finish.then(() => {
      for (const frame of frames.slice(2)) response.write(frame);
      response.end();
    });
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  const controller = new AbortController();
  const timer = setTimeout(() => {
    controller.abort();
  }, 5000);
  try {
    const response = await fetch(`http://127.0.0.1:${String(address.port)}`, {
      signal: controller.signal,
    });
    let completed = false;
    const result = readBedrockConverseStream(
      response,
      controller.signal,
      (value) => {
        if (value.phase === "bedrock_stream_first_text") markFirst();
      },
    );
    void result.then(
      () => {
        completed = true;
        markFirst();
      },
      () => {
        completed = true;
        markFirst();
      },
    );
    await first;
    assert.equal(completed, false);
    release();
    const body = (await result) as { usage: { outputTokens: number } };
    assert.equal(body.usage.outputTokens, 70);
  } finally {
    clearTimeout(timer);
    release();
    controller.abort();
    server.closeAllConnections();
    server.close();
    await once(server, "close");
  }
});

void test("Voice classifies stopped and invalid output without leaking content or retrying", async () => {
  const original = globalThis.fetch;
  const valid = {
    message:
      "Bạn có thể chọn một việc nhỏ vừa sức, rồi nghỉ một chút. Mình cùng xem điều nào phù hợp với bạn nhé.",
    language: "vi",
    authority: "SUPPORT_ONLY",
    clinicalAssessment: "NONE",
    safetyDecision: "NONE",
    eligibilityDecision: "NONE",
    businessAction: "NONE",
    thirdPartyAction: "NONE",
  };
  const cases = [
    { stopReason: "end_turn", text: JSON.stringify(valid), code: null },
    {
      stopReason: undefined,
      text: JSON.stringify(valid),
      code: "UNEXPECTED_STOP_REASON",
    },
    {
      stopReason: "stop_sequence",
      text: JSON.stringify(valid),
      code: "UNEXPECTED_STOP_REASON",
    },
    {
      stopReason: "max_tokens",
      text: '{"message":"PRIVATE_PARTIAL',
      code: "OUTPUT_TOKEN_LIMIT",
    },
    {
      stopReason: "max_tokens",
      text: JSON.stringify(valid),
      code: "OUTPUT_TOKEN_LIMIT",
    },
    {
      stopReason: "PRIVATE_STOP_REASON",
      text: JSON.stringify(valid),
      code: "UNEXPECTED_STOP_REASON",
    },
    {
      stopReason: "end_turn",
      text: "PRIVATE_INVALID_JSON",
      code: "OUTPUT_JSON_INVALID",
    },
    {
      stopReason: "end_turn",
      text: JSON.stringify({ ...valid, authority: "PRIVATE_AUTHORITY" }),
      code: "OUTPUT_SCHEMA_INVALID",
    },
    {
      stopReason: "end_turn",
      text: JSON.stringify({
        ...valid,
        message: "Tôi chẩn đoán bạn đang mắc trầm cảm",
      }),
      code: "OUTPUT_SAFETY_REJECTED",
    },
    {
      stopReason: "end_turn",
      text: undefined,
      code: "RESPONSE_ENVELOPE_INVALID",
    },
  ];
  try {
    for (const item of cases) {
      let calls = 0;
      const observations: VoiceProviderObservation[] = [];
      globalThis.fetch = (input) => {
        if (counting(input)) return counted();
        calls++;
        return Promise.resolve(
          Response.json({
            stopReason: item.stopReason,
            output: { message: { content: [{ text: item.text }] } },
            usage: {
              inputTokens: 1620,
              outputTokens: item.stopReason === "max_tokens" ? 300 : 144,
            },
          }),
        );
      };
      const work = new RoutedChatProvider({
        ...configuration,
        BEDROCK_API_KEY: "PRIVATE_KEY",
      }).reply(
        "PRIVATE_SPEECH",
        { kinds: [], prompt: "PRIVATE_CONTEXT" },
        route,
        { voice: true, voiceObserver: (value) => observations.push(value) },
      );
      if (item.code === null) assert.equal((await work).message, valid.message);
      else
        await assert.rejects(
          work,
          (error) =>
            error instanceof VoiceProviderFailure && error.code === item.code,
        );
      assert.equal(calls, 1);
      assert.equal(
        observations.filter((value) => value.phase === "provider_usage").length,
        1,
      );
      assert.equal(
        observations.some(
          (value) => value.phase === "bedrock_output_validation",
        ),
        true,
      );
      if (
        item.stopReason === undefined ||
        item.stopReason === "stop_sequence"
      ) {
        assert.deepEqual(
          observations.find(
            (value) => value.phase === "bedrock_output_validation",
          ),
          {
            phase: "bedrock_output_validation",
            stopReason:
              item.stopReason === undefined ? "MISSING" : "STOP_SEQUENCE",
          },
        );
      }
      assert.equal(
        observations.some(
          (value) => value.phase === "bedrock_response_validated",
        ),
        item.code === null,
      );
      assert.equal(JSON.stringify(observations).includes("PRIVATE"), false);
      assert.equal(JSON.stringify(observations).includes(valid.message), false);
    }
  } finally {
    globalThis.fetch = original;
  }
});

void test("Voice pilot output allowance defaults to 600 for an 800-character reply unless the pilot sets 300", async () => {
  const original = globalThis.fetch;
  try {
    for (const maxOutputTokens of [undefined, 300] as const) {
      let calls = 0;
      globalThis.fetch = (_input, init) => {
        calls++;
        if (calls === 1)
          return Promise.resolve(Response.json({ inputTokens: 1637 }));
        if (typeof init?.body !== "string")
          throw new Error("EXPECTED_STRING_BODY");
        const body = JSON.parse(init.body) as {
          inferenceConfig: { maxTokens: number };
        };
        assert.equal(body.inferenceConfig.maxTokens, maxOutputTokens ?? 600);
        assert.equal(
          Object.hasOwn(body.inferenceConfig, "stopSequences"),
          false,
        );
        return Promise.resolve(
          Response.json({
            stopReason: "end_turn",
            output: {
              message: {
                content: [
                  {
                    text: JSON.stringify({
                      message: "Mình đang lắng nghe bạn.",
                      language: "vi",
                      authority: "SUPPORT_ONLY",
                      clinicalAssessment: "NONE",
                      safetyDecision: "NONE",
                      eligibilityDecision: "NONE",
                      businessAction: "NONE",
                      thirdPartyAction: "NONE",
                    }),
                  },
                ],
              },
            },
            usage: {
              inputTokens: 1620,
              outputTokens: maxOutputTokens === 300 ? 200 : 400,
            },
          }),
        );
      };
      const result = await new RoutedChatProvider({
        ...configuration,
        BEDROCK_API_KEY: "PRIVATE_KEY",
        VOICE_PILOT: {
          startsAt: new Date(),
          endsAt: new Date(Date.now() + 60000),
          ownerIds: [randomUUID()],
          ...(maxOutputTokens === undefined ? {} : { maxOutputTokens }),
        },
      }).reply("PRIVATE_SPEECH", { kinds: [], prompt: "" }, route, {
        voice: true,
      });
      assert.equal(calls, 2);
      assert.equal(result.outputTokens, maxOutputTokens === 300 ? 200 : 400);
    }
  } finally {
    globalThis.fetch = original;
  }
});

void test("pilot phases identify counting rejection before inference and preserve real usage on invalid Bedrock output", async () => {
  const original = globalThis.fetch;
  const pilot = {
    startsAt: new Date(),
    endsAt: new Date(Date.now() + 60000),
    ownerIds: [randomUUID()],
  };
  const provider = new RoutedChatProvider({
    ...configuration,
    BEDROCK_API_KEY: "PRIVATE_KEY",
    VOICE_PILOT: pilot,
  });
  try {
    for (const [response, code] of [
      [new Response("PRIVATE_BODY", { status: 403 }), "HTTP_4XX"],
      [new Response("PRIVATE_BODY", { status: 503 }), "HTTP_5XX"],
      [Response.json({ inputTokens: 1901 }), "INPUT_BUDGET_EXCEEDED"],
      [Response.json({ inputTokens: "PRIVATE_BODY" }), "INVALID_RESPONSE"],
    ] as const) {
      const observations: VoiceProviderObservation[] = [];
      let calls = 0;
      globalThis.fetch = () => {
        calls++;
        return Promise.resolve(response);
      };
      await assert.rejects(
        provider.reply(
          "PRIVATE_SPEECH",
          { kinds: [], prompt: "PRIVATE_CONTEXT" },
          route,
          { voice: true, voiceObserver: (value) => observations.push(value) },
        ),
        (error) => error instanceof VoiceProviderFailure && error.code === code,
      );
      assert.equal(calls, 1);
      assert.deepEqual(observations, [
        { phase: "provider_prompt_prepared" },
        { phase: "token_count_requested" },
      ]);
    }
    for (const mode of ["http", "invalid-json", "invalid-output", "valid"]) {
      const observations: VoiceProviderObservation[] = [];
      let calls = 0;
      globalThis.fetch = () => {
        calls++;
        if (calls === 1)
          return Promise.resolve(Response.json({ inputTokens: 1500 }));
        return Promise.resolve(
          mode === "http"
            ? new Response("PRIVATE_BODY", { status: 429 })
            : mode === "invalid-json"
              ? new Response("PRIVATE_BODY")
              : Response.json({
                  stopReason: "end_turn",
                  output: {
                    message: {
                      content: [
                        {
                          text:
                            mode === "valid"
                              ? JSON.stringify({
                                  message: "Mình đang lắng nghe.",
                                  language: "vi",
                                  authority: "SUPPORT_ONLY",
                                  clinicalAssessment: "NONE",
                                  safetyDecision: "NONE",
                                  eligibilityDecision: "NONE",
                                  businessAction: "NONE",
                                  thirdPartyAction: "NONE",
                                })
                              : "PRIVATE_BAD_OUTPUT",
                        },
                      ],
                    },
                  },
                  usage: { inputTokens: 1500, outputTokens: 30 },
                }),
        );
      };
      const work = provider.reply(
        "PRIVATE_SPEECH",
        { kinds: [], prompt: "PRIVATE_CONTEXT" },
        route,
        { voice: true, voiceObserver: (value) => observations.push(value) },
      );
      if (mode === "valid") await work;
      else await assert.rejects(work);
      assert.equal(calls, 2);
      assert.deepEqual(observations.slice(0, 5), [
        { phase: "provider_prompt_prepared" },
        { phase: "token_count_requested" },
        { phase: "token_count_authorized", countedInputTokens: 1500 },
        { phase: "bedrock_request_body_prepared" },
        { phase: "bedrock_requested" },
      ]);
      assert.equal(
        observations.some((v) => v.phase === "bedrock_response_validated"),
        mode === "valid",
      );
      const usage = observations.find((v) => v.phase === "provider_usage");
      if (mode === "valid" || mode === "invalid-output")
        assert.deepEqual(usage, {
          phase: "provider_usage",
          inputTokens: 1500,
          outputTokens: 30,
          usageSource: "PROVIDER",
        });
      else assert.equal(usage, undefined);
      assert.equal(JSON.stringify(observations).includes("PRIVATE"), false);
    }
  } finally {
    globalThis.fetch = original;
  }
});

void test("Voice distinguishes response headers from the complete Bedrock body before validating or returning speech", async () => {
  const original = globalThis.fetch;
  try {
    for (const stopReason of ["end_turn", "stop_sequence", undefined]) {
      let release: () => void = () => undefined;
      const bodyReleased = new Promise<void>((resolve) => {
        release = resolve;
      });
      let headersObserved: () => void = () => undefined;
      const headersReceived = new Promise<void>((resolve) => {
        headersObserved = resolve;
      });
      const observations: VoiceProviderObservation[] = [];
      let calls = 0;
      let settled = false;
      globalThis.fetch = (input) => {
        if (counting(input)) return counted();
        calls++;
        return Promise.resolve(
          new Response(
            new ReadableStream<Uint8Array>({
              async start(controller) {
                await bodyReleased;
                controller.enqueue(
                  new TextEncoder().encode(
                    JSON.stringify({
                      ...(stopReason === undefined ? {} : { stopReason }),
                      output: {
                        message: {
                          content: [
                            {
                              text: JSON.stringify({
                                message: "Mình đang lắng nghe bạn.",
                                language: "vi",
                                authority: "SUPPORT_ONLY",
                                clinicalAssessment: "NONE",
                                safetyDecision: "NONE",
                                eligibilityDecision: "NONE",
                                businessAction: "NONE",
                                thirdPartyAction: "NONE",
                              }),
                            },
                          ],
                        },
                      },
                      usage: { inputTokens: 1500, outputTokens: 30 },
                    }),
                  ),
                );
                controller.close();
              },
            }),
          ),
        );
      };
      const work = new RoutedChatProvider({
        ...configuration,
        BEDROCK_API_KEY: "PRIVATE_KEY",
      }).reply(
        "PRIVATE_SPEECH",
        { kinds: [], prompt: "PRIVATE_CONTEXT" },
        route,
        {
          voice: true,
          voiceObserver: (value) => {
            observations.push(value);
            if (value.phase === "bedrock_response_received") headersObserved();
          },
        },
      );
      void work.then(
        () => {
          settled = true;
        },
        () => {
          settled = true;
        },
      );
      await headersReceived;
      assert.equal(settled, false);
      assert.deepEqual(
        observations.map((value) => value.phase),
        [
          "provider_prompt_prepared",
          "token_count_requested",
          "token_count_authorized",
          "bedrock_request_body_prepared",
          "bedrock_requested",
          "bedrock_response_received",
        ],
      );
      release();
      if (stopReason === "end_turn") {
        assert.equal((await work).message, "Mình đang lắng nghe bạn.");
      } else {
        await assert.rejects(
          work,
          (error) =>
            error instanceof VoiceProviderFailure &&
            error.code === "UNEXPECTED_STOP_REASON",
        );
      }
      assert.equal(calls, 1);
      assert.equal(
        observations.filter(
          (value) => value.phase === "bedrock_response_body_received",
        ).length,
        1,
      );
      assert.equal(
        observations.filter((value) => value.phase === "provider_usage").length,
        1,
      );
      assert.equal(
        observations.some(
          (value) => value.phase === "bedrock_response_validated",
        ),
        stopReason === "end_turn",
      );
      assert.equal(JSON.stringify(observations).includes("PRIVATE"), false);
      assert.equal(
        JSON.stringify(observations).includes("Mình đang lắng nghe"),
        false,
      );
    }
  } finally {
    globalThis.fetch = original;
  }
});

void test("voice reuses the Companion Bedrock schema/prompt/validator without tools or prompt cache", async () => {
  const original = globalThis.fetch;
  let body: Record<string, unknown> = {};
  globalThis.fetch = (input, init) => {
    if (counting(input)) return counted();
    assert.equal(typeof init?.body, "string");
    body = JSON.parse(init?.body as string) as Record<string, unknown>;
    return Promise.resolve(
      Response.json({
        stopReason: "end_turn",
        output: {
          message: {
            content: [
              {
                text: JSON.stringify({
                  message:
                    "Mình đang lắng nghe. Bạn có thể nói rõ hơn một chút không?",
                  language: "vi",
                  authority: "SUPPORT_ONLY",
                  clinicalAssessment: "NONE",
                  safetyDecision: "NONE",
                  eligibilityDecision: "NONE",
                  businessAction: "NONE",
                  thirdPartyAction: "NONE",
                }),
              },
            ],
          },
        },
        usage: { inputTokens: 120, outputTokens: 30 },
      }),
    );
  };
  try {
    const result = await new RoutedChatProvider({
      ...configuration,
      BEDROCK_API_KEY: "synthetic-bedrock-credential",
    }).reply(
      "Tôi nghe không rõ",
      { kinds: [], prompt: '[{"user":"Tôi đang lo"}]' },
      route,
      { voice: true },
    );
    assert.equal(result.inputTokens, 120);
    assert.equal(result.outputTokens, 30);
    assert.match(JSON.stringify(body.system), /MentalBridge AI Companion/);
    assert.match(
      JSON.stringify(body.system),
      /calls for a short clarification/,
    );
    assert.equal(Object.hasOwn(body, "toolConfig"), false);
    assert.equal(JSON.stringify(body).includes("cachePoint"), false);
    assert.equal(validatedAssistantMessage(result.message), result.message);
    assert.throws(() =>
      validatedAssistantMessage("Tôi chẩn đoán bạn đang mắc trầm cảm"),
    );
  } finally {
    globalThis.fetch = original;
  }
});

void test("voice-only expression accepts only neutral or comfort and every other tag becomes neutral", async () => {
  const original = globalThis.fetch;
  const core = {
    message: "Mầm đang lắng nghe bạn nè.",
    language: "vi",
    authority: "SUPPORT_ONLY",
    clinicalAssessment: "NONE",
    safetyDecision: "NONE",
    eligibilityDecision: "NONE",
    businessAction: "NONE",
    thirdPartyAction: "NONE",
  };
  assert.equal(
    Object.hasOwn(providerOutputJsonSchema.properties, "expression"),
    false,
  );
  assert.equal(
    Object.hasOwn(voiceProviderOutputJsonSchema.properties, "expression"),
    true,
  );
  assert.deepEqual(
    voiceProviderOutputJsonSchema.properties.expression.anyOf[0].enum,
    ["neutral", "comfort"],
  );
  try {
    for (const [tag, expected] of [
      ["neutral", "neutral"],
      ["comfort", "comfort"],
      ["warm", "neutral"],
      ["encourage", "neutral"],
      ["excited", "neutral"],
      [null, "neutral"],
      [undefined, "neutral"],
    ] as const) {
      globalThis.fetch = (input) =>
        counting(input)
          ? counted()
          : Promise.resolve(
              Response.json({
                stopReason: "end_turn",
                output: {
                  message: {
                    content: [
                      {
                        text: JSON.stringify({
                          ...core,
                          ...(tag === undefined ? {} : { expression: tag }),
                        }),
                      },
                    ],
                  },
                },
                usage: { inputTokens: 120, outputTokens: 30 },
              }),
            );
      const result = await new RoutedChatProvider({
        ...configuration,
        BEDROCK_API_KEY: "synthetic-bedrock-credential",
      }).reply("Tôi muốn nói thêm", { kinds: [], prompt: "[]" }, route, {
        voice: true,
      });
      assert.equal(result.expression, expected);
      assert.equal(result.message, core.message);
    }
  } finally {
    globalThis.fetch = original;
  }
});

void test("voice decodes one fenced Bedrock JSON object without relaxing schema or message safety", async () => {
  const original = globalThis.fetch;
  const valid = JSON.stringify({
    message: "Xin chào, mình đang lắng nghe bạn.",
    language: "vi",
    authority: "SUPPORT_ONLY",
    clinicalAssessment: "NONE",
    safetyDecision: "NONE",
    eligibilityDecision: "NONE",
    businessAction: "NONE",
    thirdPartyAction: "NONE",
  });
  try {
    for (const [text, accepted] of [
      [valid, true],
      ["```json\n" + valid + "\n```", true],
      ["```\r\n" + valid + "\r\n```", true],
      ["Prose\n```json\n" + valid + "\n```", false],
      ["```json\n" + valid + "\n```\nProse", false],
      ["```json\n" + valid, false],
      ["```json\n" + valid + "{}\n```", false],
      ["```json\n" + valid.replace("SUPPORT_ONLY", "NONE") + "\n```", false],
      [
        "```json\n" +
          valid.replace('"language":"vi"', '"language":"en"') +
          "\n```",
        false,
      ],
      [
        "```json\n" +
          valid.replace(
            "Xin chào, mình đang lắng nghe bạn.",
            "Tôi chẩn đoán bạn đang mắc trầm cảm",
          ) +
          "\n```",
        false,
      ],
    ] as const) {
      let calls = 0;
      globalThis.fetch = (input) => {
        if (counting(input)) return counted();
        calls++;
        return Promise.resolve(
          Response.json({
            stopReason: "end_turn",
            output: { message: { content: [{ text }] } },
            usage: { inputTokens: 120, outputTokens: 30 },
          }),
        );
      };
      const result = new RoutedChatProvider({
        ...configuration,
        BEDROCK_API_KEY: "synthetic",
      }).reply("Xin chào", { kinds: [], prompt: "" }, route, { voice: true });
      if (accepted)
        assert.equal(
          (await result).message,
          "Xin chào, mình đang lắng nghe bạn.",
        );
      else await assert.rejects(result);
      assert.equal(calls, 1);
    }
  } finally {
    globalThis.fetch = original;
  }
});

void test("worker cancellation reaches the existing Bedrock fetch and blocks late publication", async () => {
  const original = globalThis.fetch;
  let calls = 0;
  // The first Voice fetch is now CountTokens; cancellation must reach it.
  globalThis.fetch = (_input, init) =>
    new Promise((_resolve, reject) => {
      calls++;
      init?.signal?.addEventListener(
        "abort",
        () => {
          reject(new Error("synthetic private provider payload"));
        },
        { once: true },
      );
    });
  try {
    const controller = new AbortController();
    const work = new RoutedChatProvider({
      ...configuration,
      BEDROCK_API_KEY: "synthetic",
    }).reply("Tôi muốn chia sẻ", { kinds: [], prompt: "" }, route, {
      voice: true,
      signal: controller.signal,
    });
    controller.abort();
    await assert.rejects(
      work,
      (error) => error instanceof Error && !error.message.includes("private"),
    );
    assert.equal(calls, 1);
    await assert.rejects(
      new RoutedChatProvider(configuration).reply(
        "Tôi muốn chia sẻ",
        { kinds: [], prompt: "" },
        route,
        { signal: controller.signal },
      ),
    );
    assert.equal(calls, 1);
  } finally {
    globalThis.fetch = original;
  }
});
