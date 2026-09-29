import test from "node:test";
import type { Server } from "node:http";
import assert from "node:assert/strict";

import { type ExecutionContext, UnauthorizedException } from "@nestjs/common";
import { Reflector } from "@nestjs/core";
import { exportSPKI, generateKeyPair, SignJWT } from "jose";
import request from "supertest";

import { createApplication } from "./application.js";
import {
  loadConfiguration,
  type ServiceConfiguration,
} from "./configuration/configuration.js";
import { IdentityJwtVerifier } from "./security/identity-jwt-verifier.js";
import { JwtAuthenticationGuard } from "./security/jwt-authentication.guard.js";
import type { AuthenticatedRequest } from "./security/authenticated-principal.js";

const { privateKey: testPrivateKey, publicKey: testPublicKey } =
  await generateKeyPair("RS256", { extractable: true });
const testPublicKeyPem = await exportSPKI(testPublicKey);

const testConfiguration: ServiceConfiguration = {
  NODE_ENV: "test",
  PORT: 3000,
  LOG_LEVEL: "silent",
  SERVICE_NAME: "journal-ai-service",
  MONGODB_URI: "mongodb://localhost:27017",
  MONGODB_DATABASE: "mentalbridge_journal_ai_test",
  MONGODB_CONNECTION_TIMEOUT_MS: 100,
  JOURNAL_ENCRYPTION_KEY: Buffer.alloc(32, 1),
  JOURNAL_ENCRYPTION_KEY_ID: "single-key",
  JOURNAL_IDEMPOTENCY_HMAC_KEY: Buffer.alloc(32, 2),
  IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
  IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
  IDENTITY_JWT_KEY_ID: "test-key",
  IDENTITY_JWT_PUBLIC_KEY: testPublicKeyPem,
  CARE_BASE_URL: "http://localhost:8081",
  CONTENT_BASE_URL: "http://localhost:3003",
  CONTENT_TIMEOUT_MS: 2_000,
  CARE_TIMEOUT_MS: 100,
  CONSULTATION_BASE_URL: "http://localhost:8082",
  CONSULTATION_TIMEOUT_MS: 100,
  REMINDER_SERVICE_TOKEN: "test-reminder-service-token-at-least-32-characters",
  PROVIDER_MODE: "DETERMINISTIC_FAKE",
  ROUTING_POLICY_VERSION: "exact-revision-routing-v1",
  PROVIDER_APPROVAL_VERSION: null,
  FREE_PLUS_ROUTE: null,
  PREMIUM_ROUTE: null,
  GEMINI_BASE_URL: "https://generativelanguage.googleapis.com",
  GEMINI_API_KEY: null,
  OPENAI_BASE_URL: "https://api.openai.com",
  OPENAI_API_KEY: null,
  BEDROCK_REGION: "ap-southeast-1",
  BEDROCK_API_KEY: null,
  PROVIDER_TIMEOUT_MS: 30_000,
  BEDROCK_SCHEMA_WARMUP_TIMEOUT_MS: 300_000,
  BENCHMARK_ENABLED: false,
  BENCHMARK_DATASET_PATH:
    "benchmarks/datasets/exact-revision-synthetic-v1.json",
  BENCHMARK_GEMINI_ROUTE: null,
  BENCHMARK_OPENAI_ROUTE: null,
  BENCHMARK_BEDROCK_ROUTE: null,
  ANALYSIS_ENABLED: true,
  ANALYSIS_POLL_INTERVAL_MS: 250,
  ANALYSIS_LEASE_MS: 35_000,
  CHAT_RETENTION_DAYS: 90,
  CHAT_FREE_DAILY_ANSWERS: 5,
  CHAT_PLUS_DAILY_ANSWERS: 30,
  CHAT_PREMIUM_FAIR_USE_DAILY_ANSWERS: 200,
  CHAT_RATE_LIMIT_PER_MINUTE: 10,
  CHAT_DAILY_TOKEN_BUDGET: 100_000,
  CHAT_DEFAULT_TIMEZONE: "Asia/Ho_Chi_Minh",
  CHAT_ROUTING_POLICY_VERSION: "companion-chat-routing-v1",
};

const readyDependencies = {
  readinessProbe: {
    check: () => Promise.resolve(),
  },
};

void test("returns health liveness", async () => {
  const app = await createApplication(testConfiguration, readyDependencies);
  await app.init();

  try {
    const server = app.getHttpServer() as unknown as Server;

    await request(server).get("/health/live").expect(200).expect({
      status: "ok",
      service: "journal-ai-service",
      environment: "test",
    });
  } finally {
    await app.close();
  }
});

void test("returns 404 for an unknown route", async () => {
  const app = await createApplication(testConfiguration, readyDependencies);
  await app.init();

  try {
    const server = app.getHttpServer() as unknown as Server;

    await request(server)
      .get("/unknown")
      .expect("Content-Type", /application\/problem\+json/)
      .expect(404)
      .expect((response) => {
        const body = response.body as {
          code?: unknown;
          correlationId?: unknown;
        };
        assert.equal(body.code, "RESOURCE_NOT_FOUND");
        assert.equal(typeof body.correlationId, "string");
      });
  } finally {
    await app.close();
  }
});

void test("returns readiness with a correlation id", async () => {
  const app = await createApplication(testConfiguration, readyDependencies);
  await app.init();

  try {
    const server = app.getHttpServer() as unknown as Server;

    await request(server)
      .get("/health/ready")
      .set("x-correlation-id", "test-correlation-id")
      .expect("x-correlation-id", "test-correlation-id")
      .expect(200);
  } finally {
    await app.close();
  }
});

void test("returns prometheus metrics", async () => {
  const app = await createApplication(testConfiguration, readyDependencies);
  await app.init();

  try {
    const server = app.getHttpServer() as unknown as Server;

    await request(server)
      .get("/metrics")
      .expect("Content-Type", /text\/plain/)
      .expect(200)
      .expect((response) => {
        if (!response.text.includes("journal_ai_health_checks_total")) {
          throw new Error("Expected health metric to be exposed");
        }
      });
  } finally {
    await app.close();
  }
});

void test("validates configuration", () => {
  const configuration = loadConfiguration({
    NODE_ENV: "test",
    JOURNAL_AI_PORT: "3100",
    JOURNAL_AI_LOG_LEVEL: "debug",
    IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
    IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
    IDENTITY_JWT_KEY_ID: "test-key",
    IDENTITY_JWT_PUBLIC_KEY: testPublicKeyPem,
  });

  assert.equal(configuration.PORT, 3100);
  assert.equal(configuration.LOG_LEVEL, "debug");
  assert.equal(configuration.JOURNAL_ENCRYPTION_KEY_ID, "single-key");
  assert.equal(configuration.REMINDER_SERVICE_TOKEN, null);
});

void test("validates the reminder service token", () => {
  const identity = {
    NODE_ENV: "development",
    IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
    IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
    IDENTITY_JWT_KEY_ID: "test-key",
    IDENTITY_JWT_PUBLIC_KEY: testPublicKeyPem,
  };

  assert.throws(() =>
    loadConfiguration({
      ...identity,
      JOURNAL_AI_REMINDER_SERVICE_TOKEN: "too-short",
    }),
  );

  const configuration = loadConfiguration({
    ...identity,
    JOURNAL_AI_REMINDER_SERVICE_TOKEN:
      "test-reminder-service-token-at-least-32-characters",
  });
  assert.equal(
    configuration.REMINDER_SERVICE_TOKEN,
    "test-reminder-service-token-at-least-32-characters",
  );
});

void test("rejects an invalid port", () => {
  assert.throws(() =>
    loadConfiguration({
      NODE_ENV: "test",
      JOURNAL_AI_PORT: "70000",
      JOURNAL_AI_LOG_LEVEL: "info",
      IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
      IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
      IDENTITY_JWT_KEY_ID: "test-key",
      IDENTITY_JWT_PUBLIC_KEY: testPublicKeyPem,
    }),
  );
});

void test("forces deterministic providers and disables paid benchmarks in test", () => {
  const identity = {
    NODE_ENV: "test",
    IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
    IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
    IDENTITY_JWT_KEY_ID: "test-key",
    IDENTITY_JWT_PUBLIC_KEY: testPublicKeyPem,
  };
  assert.throws(() =>
    loadConfiguration({
      ...identity,
      JOURNAL_AI_PROVIDER_MODE: "APPROVED_REAL",
    }),
  );
  assert.throws(() =>
    loadConfiguration({
      ...identity,
      JOURNAL_AI_BENCHMARK_ENABLED: "true",
    }),
  );
});

void test("forces deterministic providers and disables paid benchmarks in CI", () => {
  const identity = {
    NODE_ENV: "development",
    CI: "true",
    IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
    IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
    IDENTITY_JWT_KEY_ID: "test-key",
    IDENTITY_JWT_PUBLIC_KEY: testPublicKeyPem,
  };
  assert.throws(() =>
    loadConfiguration({
      ...identity,
      JOURNAL_AI_PROVIDER_MODE: "APPROVED_REAL",
    }),
  );
  assert.throws(() =>
    loadConfiguration({
      ...identity,
      JOURNAL_AI_BENCHMARK_ENABLED: "true",
    }),
  );
});

void test("accepts a complete Gemini-only benchmark candidate", () => {
  const configuration = loadConfiguration({
    NODE_ENV: "development",
    IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
    IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
    IDENTITY_JWT_KEY_ID: "test-key",
    IDENTITY_JWT_PUBLIC_KEY: testPublicKeyPem,
    JOURNAL_AI_BENCHMARK_ENABLED: "true",
    JOURNAL_AI_GEMINI_API_KEY: "gemini-test-secret",
    JOURNAL_AI_BENCHMARK_GEMINI_MODEL: "gemini-test-model",
    JOURNAL_AI_BENCHMARK_GEMINI_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "1",
    JOURNAL_AI_BENCHMARK_GEMINI_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "2",
  });

  assert.equal(configuration.BENCHMARK_GEMINI_ROUTE?.provider, "GEMINI");
  assert.equal(configuration.BENCHMARK_OPENAI_ROUTE, null);
  assert.equal(configuration.BENCHMARK_BEDROCK_ROUTE, null);
});

void test("accepts Bedrock runtime routes and benchmark candidates with explicit credentials", () => {
  const identity = {
    NODE_ENV: "development",
    IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
    IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
    IDENTITY_JWT_KEY_ID: "test-key",
    IDENTITY_JWT_PUBLIC_KEY: testPublicKeyPem,
    AWS_BEARER_TOKEN_BEDROCK: "bedrock-test-secret",
  };
  const configuration = loadConfiguration({
    ...identity,
    JOURNAL_AI_PROVIDER_MODE: "APPROVED_REAL",
    JOURNAL_AI_PROVIDER_APPROVAL_VERSION: "approval-v1",
    JOURNAL_AI_FREE_PLUS_PROVIDER: "BEDROCK",
    JOURNAL_AI_FREE_PLUS_MODEL: "apac.bedrock-model-v1:0",
    JOURNAL_AI_FREE_PLUS_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "1",
    JOURNAL_AI_FREE_PLUS_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "2",
    JOURNAL_AI_PREMIUM_PROVIDER: "BEDROCK",
    JOURNAL_AI_PREMIUM_MODEL: "apac.bedrock-model-v1:0",
    JOURNAL_AI_PREMIUM_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "1",
    JOURNAL_AI_PREMIUM_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "2",
  });
  assert.equal(configuration.FREE_PLUS_ROUTE?.provider, "BEDROCK");
  assert.equal(configuration.BEDROCK_REGION, "ap-southeast-1");

  const benchmark = loadConfiguration({
    ...identity,
    JOURNAL_AI_BENCHMARK_ENABLED: "true",
    JOURNAL_AI_BENCHMARK_BEDROCK_MODEL: "apac.bedrock-model-v1:0",
    JOURNAL_AI_BENCHMARK_BEDROCK_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "1",
    JOURNAL_AI_BENCHMARK_BEDROCK_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "2",
  });
  assert.equal(benchmark.BENCHMARK_BEDROCK_ROUTE?.provider, "BEDROCK");
});

void test("accepts Bedrock inference-profile ARNs beyond the generic model limit", () => {
  const longProfileName = `mentalbridge-${"profile".repeat(24)}`;
  const model = `arn:aws:bedrock:ap-southeast-1:123456789012:application-inference-profile/${longProfileName}`;
  assert.ok(model.length > 128);

  const configuration = loadConfiguration({
    NODE_ENV: "development",
    IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
    IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
    IDENTITY_JWT_KEY_ID: "test-key",
    IDENTITY_JWT_PUBLIC_KEY: testPublicKeyPem,
    JOURNAL_AI_PROVIDER_MODE: "APPROVED_REAL",
    JOURNAL_AI_PROVIDER_APPROVAL_VERSION: "approval-v1",
    AWS_BEARER_TOKEN_BEDROCK: "bedrock-test-secret",
    JOURNAL_AI_FREE_PLUS_PROVIDER: "BEDROCK",
    JOURNAL_AI_FREE_PLUS_MODEL: model,
    JOURNAL_AI_FREE_PLUS_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "1",
    JOURNAL_AI_FREE_PLUS_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "2",
    JOURNAL_AI_PREMIUM_PROVIDER: "BEDROCK",
    JOURNAL_AI_PREMIUM_MODEL: model,
    JOURNAL_AI_PREMIUM_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "1",
    JOURNAL_AI_PREMIUM_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "2",
  });

  assert.equal(configuration.FREE_PLUS_ROUTE?.model, model);
  assert.equal(configuration.PREMIUM_ROUTE?.model, model);
  assert.throws(() =>
    loadConfiguration({
      NODE_ENV: "development",
      IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
      IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
      IDENTITY_JWT_KEY_ID: "test-key",
      IDENTITY_JWT_PUBLIC_KEY: testPublicKeyPem,
      JOURNAL_AI_PROVIDER_MODE: "APPROVED_REAL",
      JOURNAL_AI_PROVIDER_APPROVAL_VERSION: "approval-v1",
      JOURNAL_AI_GEMINI_API_KEY: "gemini-test-secret",
      JOURNAL_AI_FREE_PLUS_PROVIDER: "GEMINI",
      JOURNAL_AI_FREE_PLUS_MODEL: model,
      JOURNAL_AI_FREE_PLUS_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "1",
      JOURNAL_AI_FREE_PLUS_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "2",
      JOURNAL_AI_PREMIUM_PROVIDER: "GEMINI",
      JOURNAL_AI_PREMIUM_MODEL: "gemini-test-model",
      JOURNAL_AI_PREMIUM_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "1",
      JOURNAL_AI_PREMIUM_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "2",
    }),
  );
});

void test("rejects Bedrock routes without a Bedrock bearer token", () => {
  assert.throws(() =>
    loadConfiguration({
      NODE_ENV: "development",
      IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
      IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
      IDENTITY_JWT_KEY_ID: "test-key",
      IDENTITY_JWT_PUBLIC_KEY: testPublicKeyPem,
      JOURNAL_AI_PROVIDER_MODE: "APPROVED_REAL",
      JOURNAL_AI_PROVIDER_APPROVAL_VERSION: "approval-v1",
      JOURNAL_AI_FREE_PLUS_PROVIDER: "BEDROCK",
      JOURNAL_AI_FREE_PLUS_MODEL: "bedrock-model",
      JOURNAL_AI_FREE_PLUS_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "1",
      JOURNAL_AI_FREE_PLUS_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "2",
      JOURNAL_AI_PREMIUM_PROVIDER: "BEDROCK",
      JOURNAL_AI_PREMIUM_MODEL: "bedrock-model",
      JOURNAL_AI_PREMIUM_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "1",
      JOURNAL_AI_PREMIUM_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "2",
    }),
  );
});

void test("rejects an incomplete or missing benchmark candidate", () => {
  const identity = {
    NODE_ENV: "development",
    IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
    IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
    IDENTITY_JWT_KEY_ID: "test-key",
    IDENTITY_JWT_PUBLIC_KEY: testPublicKeyPem,
    JOURNAL_AI_BENCHMARK_ENABLED: "true",
  };
  assert.throws(() => loadConfiguration(identity));
  assert.throws(() =>
    loadConfiguration({
      ...identity,
      JOURNAL_AI_GEMINI_API_KEY: "gemini-test-secret",
      JOURNAL_AI_BENCHMARK_GEMINI_MODEL: "gemini-test-model",
    }),
  );
});

void test("fails closed when approved real routing is incomplete", () => {
  assert.throws(() =>
    loadConfiguration({
      NODE_ENV: "development",
      IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
      IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
      IDENTITY_JWT_KEY_ID: "test-key",
      IDENTITY_JWT_PUBLIC_KEY: testPublicKeyPem,
      JOURNAL_AI_PROVIDER_MODE: "APPROVED_REAL",
      JOURNAL_AI_PROVIDER_APPROVAL_VERSION: "approval-v1",
    }),
  );
});

void test("requires explicit MongoDB configuration in production", () => {
  assert.throws(() =>
    loadConfiguration({
      NODE_ENV: "production",
      IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
      IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
      IDENTITY_JWT_KEY_ID: "test-key",
      IDENTITY_JWT_PUBLIC_KEY: testPublicKeyPem,
    }),
  );
});

void test("requires separate production encryption and idempotency keys", () => {
  const sharedKey = Buffer.alloc(32, 9).toString("base64");
  assert.throws(() =>
    loadConfiguration({
      NODE_ENV: "production",
      JOURNAL_AI_MONGODB_URI: "mongodb://localhost:27017",
      JOURNAL_AI_MONGODB_DATABASE: "mentalbridge_journal_ai",
      JOURNAL_AI_ENCRYPTION_KEY: sharedKey,
      JOURNAL_AI_IDEMPOTENCY_HMAC_KEY: sharedKey,
      IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
      IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
      IDENTITY_JWT_KEY_ID: "test-key",
      IDENTITY_JWT_PUBLIC_KEY: testPublicKeyPem,
    }),
  );
});

void test("returns unavailable when MongoDB readiness fails", async () => {
  const app = await createApplication(testConfiguration, {
    readinessProbe: {
      check: () => Promise.reject(new Error("MongoDB unavailable")),
    },
  });
  await app.init();

  try {
    const server = app.getHttpServer() as unknown as Server;
    await request(server)
      .get("/health/ready")
      .expect("Content-Type", /application\/problem\+json/)
      .expect(503)
      .expect((response) => {
        const body = response.body as { code?: unknown };
        assert.equal(body.code, "DEPENDENCY_UNAVAILABLE");
      });
  } finally {
    await app.close();
  }
});

void test("serves consent-gated Support Guide phrasing over the internal boundary", async () => {
  const app = await createApplication(testConfiguration, {
    ...readyDependencies,
    supportGuidePhrasing: {
      consentClient: {
        check: () => Promise.resolve({ authorized: true, reason: "GRANTED" }),
      },
      provider: {
        phrase: (approvedText) =>
          Promise.resolve({
            output: { text: approvedText },
            latencyMs: 1,
            usage: {
              inputTokens: null,
              outputTokens: null,
              estimatedCostMicroUsd: null,
            },
          }),
      },
    },
  });
  await app.init();

  try {
    const now = Math.floor(Date.now() / 1_000);
    const token = await new SignJWT({ roles: ["USER"] })
      .setProtectedHeader({ alg: "RS256", kid: "test-key" })
      .setIssuer(testConfiguration.IDENTITY_JWT_ISSUER)
      .setAudience(testConfiguration.IDENTITY_JWT_AUDIENCE)
      .setSubject("11111111-1111-4111-8111-111111111111")
      .setIssuedAt(now)
      .setNotBefore(now)
      .setExpirationTime(now + 60)
      .setJti("support-guide-token-id")
      .sign(testPrivateKey);

    await request(app.getHttpServer() as unknown as Server)
      .post("/internal/v1/support-guide-phrasing")
      .set("authorization", `Bearer ${token}`)
      .set("x-correlation-id", "support-guide-correlation")
      .send({ approvedText: "Nội dung Care đã duyệt.", locale: "vi-VN" })
      .expect(201)
      .expect({
        text: "Nội dung Care đã duyệt.",
        provider: "DETERMINISTIC_FAKE",
        model: "deterministic-support-guide-phrasing-v1",
        promptVersion: "support-guide-phrasing-v1",
        schemaVersion: 1,
      });
  } finally {
    await app.close();
  }
});

void test("authorizes the scoped notification scheduler service boundary", async () => {
  const ownerId = "11111111-1111-4111-8111-111111111111";
  const app = await createApplication(testConfiguration, {
    ...readyDependencies,
    notificationActivity: {
      repository: {
        snapshot: (requestedOwnerId) => {
          assert.equal(requestedOwnerId, ownerId);
          return Promise.resolve({
            journalOccurredAt: [new Date("2026-09-27T08:00:00.000Z")],
            emotionLocalDates: ["2026-09-27"],
          });
        },
      },
      clock: { now: () => new Date("2026-09-27T12:00:00.000Z") },
    },
  });
  await app.init();

  try {
    const server = app.getHttpServer() as unknown as Server;
    const body = { ownerAccountId: ownerId, timezone: "Asia/Ho_Chi_Minh" };
    await request(server)
      .post("/internal/v1/notification-activity")
      .send(body)
      .expect(401);
    await request(server)
      .post("/internal/v1/notification-activity")
      .set("x-mentalbridge-service-token", "wrong-service-token")
      .send(body)
      .expect(401);
    await request(server)
      .post("/internal/v1/notification-activity")
      .set(
        "x-mentalbridge-service-token",
        testConfiguration.REMINDER_SERVICE_TOKEN ?? "",
      )
      .send(body)
      .expect(200)
      .expect((response) => {
        const result = response.body as {
          asOfLocalDate?: unknown;
          emotionCheckIn?: { currentStreak?: unknown };
        };
        assert.equal(result.asOfLocalDate, "2026-09-27");
        assert.equal(result.emotionCheckIn?.currentStreak, 1);
      });
  } finally {
    await app.close();
  }
});

void test("verifies Identity issuer, audience, signature and claims", async () => {
  const app = await createApplication(testConfiguration, readyDependencies);
  await app.init();

  try {
    const now = Math.floor(Date.now() / 1_000);
    const token = await new SignJWT({ roles: ["USER"] })
      .setProtectedHeader({ alg: "RS256", kid: "test-key" })
      .setIssuer(testConfiguration.IDENTITY_JWT_ISSUER)
      .setAudience(testConfiguration.IDENTITY_JWT_AUDIENCE)
      .setSubject("11111111-1111-4111-8111-111111111111")
      .setIssuedAt(now)
      .setNotBefore(now)
      .setExpirationTime(now + 60)
      .setJti("test-token-id")
      .sign(testPrivateKey);

    const principal = await app.get(IdentityJwtVerifier).verify(token);

    assert.equal(principal.accountId, "11111111-1111-4111-8111-111111111111");
    assert.deepEqual(principal.roles, ["USER"]);
    assert.equal(principal.tokenId, "test-token-id");

    const authenticatedRequest: AuthenticatedRequest = {
      headers: { authorization: `Bearer ${token}` },
    };
    const guard = new JwtAuthenticationGuard(
      app.get(Reflector),
      app.get(IdentityJwtVerifier),
    );
    const context = {
      getHandler: () => test,
      getClass: () => IdentityJwtVerifier,
      switchToHttp: () => ({
        getRequest: () => authenticatedRequest,
      }),
    } as unknown as ExecutionContext;

    assert.equal(await guard.canActivate(context), true);
    assert.equal(
      authenticatedRequest.principal?.accountId,
      "11111111-1111-4111-8111-111111111111",
    );
  } finally {
    await app.close();
  }
});

void test("fails closed when a protected request has no bearer token", async () => {
  const app = await createApplication(testConfiguration, readyDependencies);
  await app.init();

  try {
    const guard = new JwtAuthenticationGuard(
      app.get(Reflector),
      app.get(IdentityJwtVerifier),
    );
    const context = {
      getHandler: () => test,
      getClass: () => IdentityJwtVerifier,
      switchToHttp: () => ({
        getRequest: () => ({ headers: {} }),
      }),
    } as unknown as ExecutionContext;

    await assert.rejects(
      () => guard.canActivate(context),
      UnauthorizedException,
    );
  } finally {
    await app.close();
  }
});

void test("rejects a token issued for another audience", async () => {
  const app = await createApplication(testConfiguration, readyDependencies);
  await app.init();

  try {
    const now = Math.floor(Date.now() / 1_000);
    const token = await new SignJWT({ roles: ["USER"] })
      .setProtectedHeader({ alg: "RS256", kid: "test-key" })
      .setIssuer(testConfiguration.IDENTITY_JWT_ISSUER)
      .setAudience("another-api")
      .setSubject("11111111-1111-4111-8111-111111111111")
      .setIssuedAt(now)
      .setNotBefore(now)
      .setExpirationTime(now + 60)
      .setJti("test-token-id")
      .sign(testPrivateKey);

    await assert.rejects(() => app.get(IdentityJwtVerifier).verify(token));
  } finally {
    await app.close();
  }
});

void test("rejects a token signed by an untrusted key", async () => {
  const app = await createApplication(testConfiguration, readyDependencies);
  await app.init();

  try {
    const { privateKey } = await generateKeyPair("RS256");
    const now = Math.floor(Date.now() / 1_000);
    const token = await new SignJWT({ roles: ["USER"] })
      .setProtectedHeader({ alg: "RS256", kid: "test-key" })
      .setIssuer(testConfiguration.IDENTITY_JWT_ISSUER)
      .setAudience(testConfiguration.IDENTITY_JWT_AUDIENCE)
      .setSubject("11111111-1111-4111-8111-111111111111")
      .setIssuedAt(now)
      .setNotBefore(now)
      .setExpirationTime(now + 60)
      .setJti("untrusted-token-id")
      .sign(privateKey);

    await assert.rejects(() => app.get(IdentityJwtVerifier).verify(token));
  } finally {
    await app.close();
  }
});
