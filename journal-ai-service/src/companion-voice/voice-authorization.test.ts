import assert from "node:assert/strict";
import { createServer } from "node:http";
import test from "node:test";

import {
  CurrentVoiceOwnerAuthorizer,
  VoiceAuthorizationError,
  type VoiceOwnerConfiguration,
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
  voiceConsentVersion: "ai-voice-processing-capstone-v1",
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

void test("voice owner reads use distinct service identities and bind exact server subject/session", async () => {
  const calls: { url: string; init: RequestInit | undefined }[] = [];
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
  );
  const decision = await authorizer.check(
    ownerId,
    voiceSessionId,
    correlationId,
  );
  assert.equal(decision.consent.authorized, true);
  assert.equal(decision.entitlement.packageCode, "PLUS");
  assert.equal(calls.length, 2);
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
