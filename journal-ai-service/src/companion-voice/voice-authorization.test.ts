import assert from "node:assert/strict";
import { createServer } from "node:http";
import test from "node:test";

import {
  CurrentVoiceOwnerAuthorizer,
  VoiceAuthorizationError,
  type VoiceOwnerConfiguration,
  type VoiceOwnerDiagnostic,
} from "./voice-authorization.js";

const ownerId = "11111111-1111-4111-8111-111111111111";
const voiceSessionId = "22222222-2222-4222-8222-222222222222";
const correlationId = "33333333-3333-4333-8333-333333333333";
const now = new Date("2026-10-23T16:00:00Z");
const configuration: VoiceOwnerConfiguration = {
  careBaseUrl: "https://care.test",
  consultationBaseUrl: "https://consultation.test",
  careToken: "synthetic-care-voice-token".repeat(2),
  entitlementToken: "synthetic-entitlement-voice-token".repeat(2),
};

const binding = {
  ownerId,
  voiceSessionId,
  purpose: "AI_COMPANION_VOICE",
  checkedAt: now.toISOString(),
};
const consent = {
  ...binding,
  aiProcessingGranted: true,
  voiceProcessingGranted: true,
  voiceConsentVersion: "ai-voice-processing-capstone-v2",
  authorized: true,
};
const entitlement = {
  ...binding,
  packageCode: "PLUS",
  source: "PAID",
  entitlementVersion: "paid:7",
  validUntil: "2026-11-01T00:00:00Z",
};
const response = (value: unknown, headers = { "Cache-Control": "no-store" }) =>
  Response.json(value, { headers });
const transport =
  (
    overrides: {
      consent?: unknown;
      entitlement?: unknown;
      headers?: { "Cache-Control": string };
    } = {},
  ): typeof fetch =>
  (input) =>
    Promise.resolve(
      response(
        (input instanceof Request ? input.url : input.toString()).includes(
          "voice-consent",
        )
          ? (overrides.consent ?? consent)
          : (overrides.entitlement ?? entitlement),
        overrides.headers,
      ),
    );
const isCareRequest = (input: Parameters<typeof fetch>[0]) =>
  (input instanceof Request ? input.url : input.toString()).startsWith(
    configuration.careBaseUrl,
  );

void test("voice owner reads use distinct service identities and bind exact server subject/session", async () => {
  const calls: { url: string; init: RequestInit | undefined }[] = [];
  const diagnostics: VoiceOwnerDiagnostic[] = [];
  const authorizer = new CurrentVoiceOwnerAuthorizer(
    configuration,
    () => now,
    async (input, init) => {
      calls.push({
        url: input instanceof Request ? input.url : input.toString(),
        init,
      });
      return transport()(input, init);
    },
    (value) => diagnostics.push(value),
  );
  const decision = await authorizer.check(
    ownerId,
    voiceSessionId,
    correlationId,
  );
  assert.equal(decision.consent.authorized, true);
  assert.equal(decision.entitlement.packageCode, "PLUS");
  assert.equal(calls.length, 2);
  assert.deepEqual(diagnostics, []);
  for (const call of calls) {
    assert.ok(call.init);
    assert.equal(call.init.method, "POST");
    assert.equal(call.init.redirect, "error");
    assert.ok(call.init.signal instanceof AbortSignal);
    const headers = new Headers(call.init.headers);
    assert.equal(headers.has("Authorization"), false);
    assert.equal(headers.get("X-Correlation-Id"), correlationId);
    assert.equal(typeof call.init.body, "string");
    assert.deepEqual(JSON.parse(call.init.body as string), {
      ownerId,
      voiceSessionId,
      purpose: "AI_COMPANION_VOICE",
    });
    const isCare = call.url.startsWith(configuration.careBaseUrl);
    assert.equal(
      headers.get("X-MentalBridge-Voice-Care-Token"),
      isCare ? configuration.careToken : null,
    );
    assert.equal(
      headers.get("X-MentalBridge-Voice-Entitlement-Token"),
      isCare ? null : configuration.entitlementToken,
    );
  }
});

for (const [label, override, code] of [
  [
    "wrong owner",
    { consent: { ...consent, ownerId: voiceSessionId } },
    "CONSENT_UNAVAILABLE",
  ],
  [
    "wrong session",
    { entitlement: { ...entitlement, voiceSessionId: ownerId } },
    "ENTITLEMENT_UNAVAILABLE",
  ],
  [
    "stale decision",
    { consent: { ...consent, checkedAt: "2026-10-23T15:59:00Z" } },
    "CONSENT_UNAVAILABLE",
  ],
  [
    "future decision",
    { entitlement: { ...entitlement, checkedAt: "2026-10-23T16:01:00Z" } },
    "ENTITLEMENT_UNAVAILABLE",
  ],
  [
    "contradictory consent",
    { consent: { ...consent, voiceProcessingGranted: false } },
    "CONSENT_UNAVAILABLE",
  ],
  [
    "old voice version",
    { consent: { ...consent, voiceConsentVersion: "old" } },
    "CONSENT_UNAVAILABLE",
  ],
  [
    "paid without expiry",
    { entitlement: { ...entitlement, validUntil: undefined } },
    "ENTITLEMENT_UNAVAILABLE",
  ],
  [
    "expired entitlement",
    { entitlement: { ...entitlement, validUntil: now.toISOString() } },
    "ENTITLEMENT_UNAVAILABLE",
  ],
  [
    "default-free paid package",
    { entitlement: { ...entitlement, source: "DEFAULT_FREE" } },
    "ENTITLEMENT_UNAVAILABLE",
  ],
  [
    "unknown content",
    { consent: { ...consent, transcript: "private synthetic input" } },
    "CONSENT_UNAVAILABLE",
  ],
  [
    "cacheable authority",
    { headers: { "Cache-Control": "public" } },
    "CONSENT_UNAVAILABLE",
  ],
] as const) {
  void test(`voice owner client fails closed on ${label}`, async () => {
    const authorizer = new CurrentVoiceOwnerAuthorizer(
      configuration,
      () => now,
      transport(override),
    );
    await assert.rejects(
      authorizer.check(ownerId, voiceSessionId, correlationId),
      (error: unknown) =>
        error instanceof VoiceAuthorizationError && error.code === code,
    );
  });
}

void test("withdrawn consents and current FREE are explicit owner decisions, not stale-positive fallbacks", async () => {
  const authorizer = new CurrentVoiceOwnerAuthorizer(
    configuration,
    () => now,
    transport({
      consent: { ...consent, aiProcessingGranted: false, authorized: false },
      entitlement: {
        ...binding,
        packageCode: "FREE",
        source: "DEFAULT_FREE",
        entitlementVersion: "free:0",
      },
    }),
  );
  const decision = await authorizer.check(
    ownerId,
    voiceSessionId,
    correlationId,
  );
  assert.equal(decision.consent.authorized, false);
  assert.equal(decision.entitlement.packageCode, "FREE");
});

void test("missing service credential and malformed/downstream errors stay sanitized", async () => {
  for (const invoke of [
    new CurrentVoiceOwnerAuthorizer(
      { ...configuration, careToken: null },
      () => now,
      transport(),
    ),
    new CurrentVoiceOwnerAuthorizer(
      configuration,
      () => now,
      () => Promise.reject(new Error("private-provider-body")),
    ),
    new CurrentVoiceOwnerAuthorizer(
      configuration,
      () => now,
      () => Promise.resolve(new Response("private-provider-body")),
    ),
    new CurrentVoiceOwnerAuthorizer(
      configuration,
      () => now,
      () =>
        Promise.resolve(new Response("private-provider-body", { status: 503 })),
    ),
  ]) {
    await assert.rejects(
      invoke.check(ownerId, voiceSessionId, correlationId),
      (error: unknown) => {
        assert.ok(error instanceof VoiceAuthorizationError);
        assert.equal(error.message, "CONSENT_UNAVAILABLE");
        assert.equal(error.cause, undefined);
        return true;
      },
    );
  }
});

void test("a stalled owner REST response is bounded without retry", async () => {
  let requests = 0;
  const server = createServer(() => {
    requests += 1;
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  try {
    const authorizer = new CurrentVoiceOwnerAuthorizer({
      ...configuration,
      careBaseUrl: `http://127.0.0.1:${String(address.port)}`,
      consultationBaseUrl: `http://127.0.0.1:${String(address.port)}`,
    });
    const started = Date.now();
    await assert.rejects(
      authorizer.check(ownerId, voiceSessionId, correlationId),
      VoiceAuthorizationError,
    );
    assert.ok(Date.now() - started < 5_000);
    assert.equal(requests, 2);
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve) => {
      server.close(() => {
        resolve();
      });
    });
  }
});

void test("owner failure diagnostics expose only closed metadata and preserve public errors", async () => {
  const secret = "PRIVATE_AUDIO_TRANSCRIPT_KEY_BODY";
  const cases: [
    VoiceOwnerDiagnostic["reason"],
    () => Response | Promise<Response>,
  ][] = [
    ["TRANSPORT", () => Promise.reject(new Error(secret))],
    ["HTTP_4XX", () => new Response(secret, { status: 403 })],
    ["HTTP_5XX", () => new Response(secret, { status: 503 })],
    ["HTTP_OTHER", () => new Response(secret, { status: 302 })],
    ["INVALID_HEADERS", () => new Response(secret)],
    [
      "INVALID_JSON",
      () => new Response(secret, { headers: { "Cache-Control": "no-store" } }),
    ],
    ["INVALID_RESPONSE", () => response({ ...consent, transcript: secret })],
    [
      "BINDING_MISMATCH",
      () => response({ ...consent, ownerId: voiceSessionId }),
    ],
    [
      "STALE_DECISION",
      () =>
        response({
          ...consent,
          checkedAt: new Date(now.getTime() - 2_001).toISOString(),
        }),
    ],
    [
      "FUTURE_DECISION",
      () =>
        response({
          ...consent,
          checkedAt: new Date(now.getTime() + 26).toISOString(),
        }),
    ],
  ];
  for (const [reason, fail] of cases) {
    const diagnostics: VoiceOwnerDiagnostic[] = [];
    const authorizer = new CurrentVoiceOwnerAuthorizer(
      configuration,
      () => now,
      async (input, init) =>
        isCareRequest(input) ? fail() : transport()(input, init),
      (value) => diagnostics.push(value),
    );
    await assert.rejects(
      authorizer.check(ownerId, voiceSessionId, correlationId),
      (error: unknown) => {
        assert.ok(error instanceof VoiceAuthorizationError);
        assert.equal(error.code, "CONSENT_UNAVAILABLE");
        assert.equal(error.message, error.code);
        assert.equal(error.cause, undefined);
        return true;
      },
    );
    assert.equal(diagnostics.length, 1);
    assert.equal(diagnostics[0]?.reason, reason);
    assert.equal(diagnostics[0].service, "CARE");
    assert.ok(
      Number.isInteger(diagnostics[0].elapsedMs) &&
        diagnostics[0].elapsedMs >= 0,
    );
    assert.ok(
      Object.keys(diagnostics[0]).every((key) =>
        ["service", "reason", "elapsedMs", "status", "decisionAgeMs"].includes(
          key,
        ),
      ),
    );
    for (const value of [
      secret,
      ownerId,
      voiceSessionId,
      correlationId,
      configuration.careToken,
      configuration.entitlementToken,
    ])
      assert.equal(
        JSON.stringify(diagnostics).includes(value ?? "unused"),
        false,
      );
    if (reason.startsWith("HTTP_")) assert.ok(diagnostics[0].status);
  }
  for (const [config, override, expected] of [
    [{ ...configuration, careToken: null }, {}, "MISSING_CREDENTIAL"],
    [
      configuration,
      { entitlement: { ...entitlement, validUntil: now.toISOString() } },
      "EXPIRED_ENTITLEMENT",
    ],
  ] as const) {
    const diagnostics: VoiceOwnerDiagnostic[] = [];
    const authorizer = new CurrentVoiceOwnerAuthorizer(
      config,
      () => now,
      transport(override),
      (value) => diagnostics.push(value),
    );
    await assert.rejects(
      authorizer.check(ownerId, voiceSessionId, correlationId),
      VoiceAuthorizationError,
    );
    assert.equal(diagnostics[0]?.reason, expected);
    assert.equal(
      diagnostics[0].service,
      expected === "MISSING_CREDENTIAL" ? "CARE" : "CONSULTATION",
    );
  }
});

void test("freshness accepts exactly 2000ms and rechecks early decisions at common return", async () => {
  const diagnostics: VoiceOwnerDiagnostic[] = [];
  const boundary = new CurrentVoiceOwnerAuthorizer(
    configuration,
    () => now,
    transport({
      consent: {
        ...consent,
        checkedAt: new Date(now.getTime() - 2_000).toISOString(),
      },
    }),
    (value) => diagnostics.push(value),
  );
  await boundary.check(ownerId, voiceSessionId, correlationId);
  assert.equal(diagnostics.length, 0);
  let clock = now;
  const aged = new CurrentVoiceOwnerAuthorizer(
    configuration,
    () => clock,
    async (input, init) => {
      if (isCareRequest(input)) return transport()(input, init);
      await new Promise((resolve) => setTimeout(resolve, 5));
      clock = new Date(now.getTime() + 2_001);
      return response({ ...entitlement, checkedAt: clock.toISOString() });
    },
    (value) => diagnostics.push(value),
  );
  await assert.rejects(aged.check(ownerId, voiceSessionId, correlationId), {
    code: "CONSENT_UNAVAILABLE",
  });
  assert.deepEqual(diagnostics, [
    {
      service: "CARE",
      reason: "STALE_DECISION",
      elapsedMs: 0,
      decisionAgeMs: 2_001,
    },
  ]);
});

void test("small clock lead waits for the same decision without accepting a future timestamp", async () => {
  for (const moving of [false, true]) {
    const diagnostics: VoiceOwnerDiagnostic[] = [];
    let instant = now.getTime();
    let reads = 0;
    const authorizer = new CurrentVoiceOwnerAuthorizer(
      configuration,
      () => new Date(instant),
      (input) => {
        reads += 1;
        const isCare = isCareRequest(input);
        if (isCare && moving)
          setTimeout(() => {
            instant += 1;
          }, 1);
        return Promise.resolve(
          response({
            ...(isCare ? consent : entitlement),
            checkedAt: new Date(now.getTime() + (isCare ? 1 : 0)).toISOString(),
          }),
        );
      },
      (value) => diagnostics.push(value),
    );
    const started = Date.now();
    if (moving) await authorizer.check(ownerId, voiceSessionId, correlationId);
    else
      await assert.rejects(
        authorizer.check(ownerId, voiceSessionId, correlationId),
        { code: "CONSENT_UNAVAILABLE" },
      );
    assert.equal(reads, 2);
    assert.ok(Date.now() - started < 250);
    assert.equal(
      diagnostics[0]?.reason,
      moving ? "CLOCK_CAUGHT_UP" : "FUTURE_DECISION",
    );
    assert.equal(diagnostics[0].decisionAgeMs, -1);
  }
});

for (const mode of ["body", "clock catch-up"] as const) {
  void test(`${mode} timeout retains the original deadline`, async () => {
    const diagnostics: VoiceOwnerDiagnostic[] = [];
    const authorizer = new CurrentVoiceOwnerAuthorizer(
      configuration,
      () => now,
      async (input, init) => {
        if (!isCareRequest(input)) return transport()(input, init);
        if (mode === "clock catch-up") {
          await new Promise((resolve) => setTimeout(resolve, 1_990));
          return response({
            ...consent,
            checkedAt: new Date(now.getTime() + 1).toISOString(),
          });
        }
        const body = new ReadableStream({
          start(controller) {
            controller.enqueue(
              new TextEncoder().encode("private-incomplete-body"),
            );
            init?.signal?.addEventListener(
              "abort",
              () => {
                controller.error(new Error("private-abort"));
              },
              { once: true },
            );
          },
        });
        return new Response(body, { headers: { "Cache-Control": "no-store" } });
      },
      (value) => diagnostics.push(value),
    );
    const started = Date.now();
    const keepAlive = setTimeout(() => undefined, 3_000);
    try {
      await assert.rejects(
        authorizer.check(ownerId, voiceSessionId, correlationId),
        { code: "CONSENT_UNAVAILABLE" },
      );
    } finally {
      clearTimeout(keepAlive);
    }
    assert.ok(Date.now() - started >= 1_900 && Date.now() - started < 3_000);
    assert.equal(diagnostics[0]?.reason, "TIMEOUT");
    assert.equal(JSON.stringify(diagnostics).includes("private"), false);
  });
}
