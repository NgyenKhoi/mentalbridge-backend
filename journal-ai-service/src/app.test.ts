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
  IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
  IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
  IDENTITY_JWT_KEY_ID: "test-key",
  IDENTITY_JWT_PUBLIC_KEY: testPublicKeyPem,
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
