import assert from "node:assert/strict";
import test from "node:test";
import type { Server } from "node:http";
import { exportSPKI, generateKeyPair, SignJWT } from "jose";
import request from "supertest";

import { createApplication } from "../application.js";
import { loadConfiguration } from "../configuration/configuration.js";
import {
  VoiceAuthorizationError,
  type VoiceOwnerDecision,
} from "./voice-authorization.js";
import type { VoiceAccessLedger, VoiceEligibility } from "./voice-access.js";

const ownerId = "11111111-1111-4111-8111-111111111111";
const otherId = "22222222-2222-4222-8222-222222222222";
const correlationId = "33333333-3333-4333-8333-333333333333";
const now = new Date("2026-10-23T16:00:00Z");
const { privateKey, publicKey } = await generateKeyPair("RS256", {
  extractable: true,
});
const identity = {
  NODE_ENV: "test",
  CI: "true",
  IDENTITY_JWT_ISSUER: "https://identity.voice.test",
  IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
  IDENTITY_JWT_KEY_ID: "voice-test-key",
  IDENTITY_JWT_PUBLIC_KEY: await exportSPKI(publicKey),
  JOURNAL_AI_LOG_LEVEL: "silent",
  JOURNAL_AI_ANALYSIS_ENABLED: "false",
};
const token = (role = "USER") =>
  new SignJWT({ roles: [role] })
    .setProtectedHeader({ alg: "RS256", kid: identity.IDENTITY_JWT_KEY_ID })
    .setIssuer(identity.IDENTITY_JWT_ISSUER)
    .setAudience(identity.IDENTITY_JWT_AUDIENCE)
    .setSubject(ownerId)
    .setIssuedAt()
    .setNotBefore("0s")
    .setExpirationTime("5m")
    .setJti(correlationId)
    .sign(privateKey);

void test("Voice HTTP boundary isolates JWT/USER checks, no-store and current-owner eligibility", async () => {
  const checkedOwners: string[] = [];
  let unavailable: "CONSENT_UNAVAILABLE" | "ENTITLEMENT_UNAVAILABLE" | null =
    null;
  let voiceGranted = true;
  let plan: "FREE" | "PLUS" | "PREMIUM" = "PLUS";
  let providerReady = true;
  const readOnlyLedger: VoiceAccessLedger = {
    snapshot: (_owner, currentPlan) =>
      Promise.resolve({
        policyVersion: "companion-voice-policy-v1",
        timeZone: "Asia/Ho_Chi_Minh",
        resetAt: new Date("2026-10-23T17:00:00Z"),
        quotaDay: "2026-10-23",
        plan: currentPlan,
        usedSessions: 0,
        reservedSessions: 0,
        usedSeconds: 0,
        reservedSeconds: 0,
        remainingSessions: currentPlan === "FREE" ? 0 : 2,
        remainingSeconds: currentPlan === "FREE" ? 0 : 600,
        startAttempts: 0,
        ownerActive: false,
        globalAvailable: 2,
        rateLimited: false,
      }),
    replay: () => {
      throw new Error("GET must not write or reserve");
    },
    recordStartAttempt: () => {
      throw new Error("GET must not count attempts");
    },
    releaseStartAttempt: () => {
      throw new Error("GET must not change attempt claims");
    },
    reserve: () => {
      throw new Error("GET must not admit");
    },
    findOwned: () => Promise.resolve(null),
    requestEnd: () => {
      throw new Error("GET must not mutate a session");
    },
    renewAuthority: () => {
      throw new Error("GET cannot issue a lease");
    },
    reauthorizationCandidates: () => Promise.resolve([]),
    reconcile: () => Promise.resolve({ ended: 0, purged: 0, quarantined: 0 }),
  };
  const app = await createApplication(loadConfiguration(identity), {
    readinessProbe: { check: () => Promise.resolve() },
    companionVoice: {
      ledger: readOnlyLedger,
      maintenance: false,
      clock: () => now,
      providerReady: () => providerReady,
      authorizer: {
        check: (owner, session): Promise<VoiceOwnerDecision> => {
          checkedOwners.push(owner);
          if (unavailable) throw new VoiceAuthorizationError(unavailable);
          const binding = {
            ownerId: owner,
            voiceSessionId: session,
            purpose: "AI_COMPANION_VOICE" as const,
            checkedAt: now.toISOString(),
          };
          return Promise.resolve({
            consent: {
              ...binding,
              aiProcessingGranted: true,
              voiceProcessingGranted: voiceGranted,
              voiceConsentVersion: "ai-voice-processing-capstone-v1",
              authorized: voiceGranted,
            },
            entitlement: {
              ...binding,
              packageCode: plan,
              source: plan === "FREE" ? "DEFAULT_FREE" : "PAID",
              entitlementVersion: "voice-owner-policy:4",
              ...(plan !== "FREE"
                ? { validUntil: "2026-11-01T00:00:00Z" }
                : {}),
            },
          });
        },
      },
    },
  });
  await app.init();
  const server = app.getHttpServer() as Server;
  const get = () =>
    request(server)
      .get("/api/v1/ai-companion/voice-eligibility")
      .set("X-Correlation-Id", correlationId);
  const assertProblem = (
    body: Record<string, unknown>,
    code: string,
    status: number,
  ) => {
    assert.deepEqual(Object.keys(body).sort(), [
      "code",
      "correlationId",
      "status",
      "title",
      "type",
    ]);
    assert.equal(body.code, code);
    assert.equal(body.status, status);
    assert.equal(body.correlationId, correlationId);
    assert.match(String(body.type), /^https:\/\//);
  };
  try {
    const legacyCorrelation = await request(server)
      .get("/api/v1/ai-companion/voice-eligibility")
      .set("X-Correlation-Id", "trace-123")
      .expect(401);
    const legacyProblem = legacyCorrelation.body as Record<string, unknown>;
    assert.match(
      String(legacyProblem.correlationId),
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    );
    assert.equal(
      legacyProblem.correlationId,
      legacyCorrelation.headers["x-correlation-id"],
    );
    const missing = await get().expect(401).expect("Cache-Control", "no-store");
    assertProblem(
      missing.body as Record<string, unknown>,
      "UNAUTHENTICATED",
      401,
    );
    const forged = await get()
      .set("Authorization", "Bearer forged-token")
      .expect(401)
      .expect("Cache-Control", "no-store");
    assertProblem(
      forged.body as Record<string, unknown>,
      "UNAUTHENTICATED",
      401,
    );
    const worker = await get()
      .set("X-MentalBridge-Voice-Worker-Token", "synthetic-worker-token")
      .expect(401)
      .expect("Cache-Control", "no-store");
    assertProblem(
      worker.body as Record<string, unknown>,
      "UNAUTHENTICATED",
      401,
    );
    const specialist = await get()
      .set("Authorization", `Bearer ${await token("SPECIALIST")}`)
      .expect(403)
      .expect("Cache-Control", "no-store");
    assertProblem(specialist.body as Record<string, unknown>, "FORBIDDEN", 403);
    assert.equal(checkedOwners.length, 0);
    const result = await get()
      .query({ ownerId: otherId, packageCode: "PREMIUM", now: "2099-01-01" })
      .set("Authorization", `Bearer ${await token()}`)
      .expect(200)
      .expect("Cache-Control", "no-store");
    const eligibility = result.body as VoiceEligibility;
    assert.equal(eligibility.state, "ELIGIBLE");
    assert.equal(eligibility.packageCode, "PLUS");
    assert.equal(eligibility.maxSessionSeconds, 300);
    assert.equal(eligibility.availableSessionSeconds, 300);
    assert.deepEqual(checkedOwners, [ownerId]);
    voiceGranted = false;
    const denied = await get()
      .set("Authorization", `Bearer ${await token()}`)
      .expect(200)
      .expect("Cache-Control", "no-store");
    assert.equal(
      (denied.body as VoiceEligibility).state,
      "VOICE_CONSENT_REQUIRED",
    );
    assert.equal((denied.body as VoiceEligibility).eligible, false);
    assert.equal((denied.body as VoiceEligibility).availableSessionSeconds, 0);
    voiceGranted = true;
    plan = "FREE";
    const free = await get()
      .set("Authorization", `Bearer ${await token()}`)
      .expect(200);
    assert.equal((free.body as VoiceEligibility).state, "FREE_LOCKED");
    assert.equal((free.body as VoiceEligibility).quota.remainingSessions, 0);
    plan = "PLUS";
    providerReady = false;
    const disabled = await get()
      .set("Authorization", `Bearer ${await token()}`)
      .expect(200);
    assert.equal(
      (disabled.body as VoiceEligibility).state,
      "PROVIDER_UNAVAILABLE",
    );
    for (const code of [
      "CONSENT_UNAVAILABLE",
      "ENTITLEMENT_UNAVAILABLE",
    ] as const) {
      unavailable = code;
      const failed = await get()
        .set("Authorization", `Bearer ${await token()}`)
        .expect(503)
        .expect("Cache-Control", "no-store");
      assertProblem(failed.body as Record<string, unknown>, code, 503);
    }
    const dependencyCorrelation = await request(server)
      .get("/api/v1/ai-companion/voice-eligibility")
      .set("X-Correlation-Id", "trace-123")
      .set("Authorization", `Bearer ${await token()}`)
      .expect(503);
    const dependencyProblem = dependencyCorrelation.body as Record<
      string,
      unknown
    >;
    assert.match(
      String(dependencyProblem.correlationId),
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    );
    assert.equal(
      dependencyProblem.correlationId,
      dependencyCorrelation.headers["x-correlation-id"],
    );
  } finally {
    await app.close();
  }
});

void test("Voice credentials are optional when disabled and cannot alias another service purpose", () => {
  const empty = loadConfiguration({
    ...identity,
    JOURNAL_AI_VOICE_CARE_SERVICE_TOKEN: "",
    JOURNAL_AI_VOICE_ENTITLEMENT_SERVICE_TOKEN: "",
  });
  assert.deepEqual(empty.VOICE_OWNER_TOKENS, { care: null, entitlement: null });
  const credential = "synthetic-purpose-only-credential-32-characters";
  assert.throws(() =>
    loadConfiguration({
      ...identity,
      JOURNAL_AI_VOICE_CARE_SERVICE_TOKEN: credential,
      JOURNAL_AI_VOICE_ENTITLEMENT_SERVICE_TOKEN: credential,
    }),
  );
  assert.throws(() =>
    loadConfiguration({
      ...identity,
      JOURNAL_AI_VOICE_CARE_SERVICE_TOKEN: credential,
      JOURNAL_AI_REMINDER_SERVICE_TOKEN: credential,
    }),
  );
  for (const invalid of ["short", " ".repeat(32), `${credential}\n`])
    assert.throws(() =>
      loadConfiguration({
        ...identity,
        JOURNAL_AI_VOICE_CARE_SERVICE_TOKEN: invalid,
      }),
    );
});
