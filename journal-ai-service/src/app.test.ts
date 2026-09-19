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
  CARE_TIMEOUT_MS: 100,
  CONSULTATION_BASE_URL: "http://localhost:8082",
  CONSULTATION_TIMEOUT_MS: 100,
  PROVIDER_MODE: "DETERMINISTIC_FAKE",
  ROUTING_POLICY_VERSION: "exact-revision-routing-v1",
  PROVIDER_APPROVAL_VERSION: null,
  FREE_PLUS_ROUTE: null,
  PREMIUM_ROUTE: null,
  GEMINI_BASE_URL: "https://generativelanguage.googleapis.com",
  GEMINI_API_KEY: null,
  OPENAI_BASE_URL: "https://api.openai.com",
  OPENAI_API_KEY: null,
  PROVIDER_TIMEOUT_MS: 30_000,
  BENCHMARK_ENABLED: false,
  BENCHMARK_DATASET_PATH:
    "benchmarks/datasets/exact-revision-synthetic-v1.json",
  BENCHMARK_GEMINI_ROUTE: null,
  BENCHMARK_OPENAI_ROUTE: null,
  ANALYSIS_ENABLED: true,
  ANALYSIS_POLL_INTERVAL_MS: 250,
  ANALYSIS_LEASE_MS: 35_000,
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
