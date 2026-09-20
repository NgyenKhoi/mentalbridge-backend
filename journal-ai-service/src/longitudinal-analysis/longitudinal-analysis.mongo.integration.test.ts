import assert from "node:assert/strict";
import test from "node:test";
import type { Server } from "node:http";
import { createRequire } from "node:module";
import { exportSPKI, generateKeyPair, SignJWT } from "jose";
import { MongoClient } from "mongodb";
import { MongoMemoryServer } from "mongodb-memory-server";
import request from "supertest";

import { createApplication } from "../application.js";
import type { ConsentClient } from "../analysis/analysis.js";
import type { ServiceConfiguration } from "../configuration/configuration.js";

const require = createRequire(import.meta.url);
const migrations = [
  require("../../migrations/001_journal_entries_baseline.cjs"),
  require("../../migrations/002_journal_mutation_commands.cjs"),
  require("../../migrations/003_journal_replay_snapshots_and_cursor_index.cjs"),
  require("../../migrations/004_journal_revision_mood.cjs"),
  require("../../migrations/005_exact_revision_analysis.cjs"),
  require("../../migrations/006_daily_emotion_check_ins.cjs"),
  require("../../migrations/007_entitlement_aware_model_routing.cjs"),
  require("../../migrations/008_ai_benchmark_metadata.cjs"),
  require("../../migrations/009_longitudinal_context_analysis.cjs"),
] as { up(database: unknown): Promise<void> }[];

const DAY_MS = 24 * 60 * 60 * 1_000;

void test("runs the bounded longitudinal owner and Care flow against migrated MongoDB", async () => {
  const mongo = await MongoMemoryServer.create();
  const client = new MongoClient(mongo.getUri());
  const databaseName = "longitudinal_mb371_integration";
  const database = client.db(databaseName);
  for (const migration of migrations) await migration.up(database);

  const { privateKey, publicKey } = await generateKeyPair("RS256", {
    extractable: true,
  });
  const configuration: ServiceConfiguration = {
    NODE_ENV: "test",
    PORT: 3000,
    LOG_LEVEL: "silent",
    SERVICE_NAME: "journal-ai-service",
    MONGODB_URI: mongo.getUri(),
    MONGODB_DATABASE: databaseName,
    MONGODB_CONNECTION_TIMEOUT_MS: 2_000,
    JOURNAL_ENCRYPTION_KEY: Buffer.alloc(32, 31),
    JOURNAL_ENCRYPTION_KEY_ID: "single-key",
    JOURNAL_IDEMPOTENCY_HMAC_KEY: Buffer.alloc(32, 32),
    IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
    IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
    IDENTITY_JWT_KEY_ID: "longitudinal-integration-key",
    IDENTITY_JWT_PUBLIC_KEY: await exportSPKI(publicKey),
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
    ANALYSIS_POLL_INTERVAL_MS: 25,
    ANALYSIS_LEASE_MS: 35_000,
  };
  const tokenFor = (subject: string) =>
    new SignJWT({ roles: ["USER"] })
      .setProtectedHeader({
        alg: "RS256",
        kid: configuration.IDENTITY_JWT_KEY_ID,
      })
      .setIssuer(configuration.IDENTITY_JWT_ISSUER)
      .setAudience(configuration.IDENTITY_JWT_AUDIENCE)
      .setSubject(subject)
      .setIssuedAt()
      .setNotBefore("0s")
      .setExpirationTime("5m")
      .setJti(`longitudinal-${subject}`)
      .sign(privateKey);
  const ownerId = "11111111-1111-4111-8111-111111111111";
  const otherId = "22222222-2222-4222-8222-222222222222";
  const [ownerToken, otherToken] = await Promise.all([
    tokenFor(ownerId),
    tokenFor(otherId),
  ]);
  const forwardedTokens: string[] = [];
  const consent: ConsentClient = {
    check: (bearer) => {
      forwardedTokens.push(bearer);
      return Promise.resolve({ authorized: true, reason: "GRANTED" });
    },
  };
  const app = await createApplication(configuration, {
    readinessProbe: { check: () => Promise.resolve() },
    longitudinalAnalysis: {
      consentClient: consent,
      entitlementClient: {
        current: () =>
          Promise.resolve({
            packageCode: "FREE" as const,
            source: "DEFAULT_FREE" as const,
            policyVersion: "service-entitlement-v1",
            version: 0,
          }),
      },
    },
  });
  await app.init();
  const server = app.getHttpServer() as unknown as Server;

  try {
    const currentEnd = new Date(Date.now() - DAY_MS);
    const currentStart = new Date(currentEnd.getTime() - 7 * DAY_MS);
    const previousEnd = currentStart;
    const previousStart = new Date(previousEnd.getTime() - 7 * DAY_MS);
    const journalIds: string[] = [];
    for (let index = 0; index < 8; index += 1) {
      const periodStart = index < 4 ? previousStart : currentStart;
      const occurredAt = new Date(
        periodStart.getTime() + ((index % 4) + 1) * DAY_MS,
      );
      const response = await request(server)
        .post("/api/v1/journals")
        .set("Authorization", `Bearer ${ownerToken}`)
        .set("Idempotency-Key", `longitudinal-journal-create-${String(index)}`)
        .send({
          clientEntryId: `33333333-3333-4333-8333-${String(index + 1).padStart(12, "0")}`,
          occurredAt: occurredAt.toISOString(),
          content: {
            text: `sensitive synthetic longitudinal entry ${String(index)}`,
          },
          mood: "OKAY",
        })
        .expect(201);
      journalIds.push(String((response.body as { id: unknown }).id));
    }

    const comparison = {
      previousPeriod: {
        startAt: previousStart.toISOString(),
        endAt: previousEnd.toISOString(),
      },
      currentPeriod: {
        startAt: currentStart.toISOString(),
        endAt: currentEnd.toISOString(),
      },
      excludedJournalIds: [journalIds[0], journalIds[4]],
    };
    const accepted = await request(server)
      .post("/api/v1/longitudinal-analysis-jobs")
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "longitudinal-analysis-command-0001")
      .send(comparison)
      .expect(202);
    const jobId = String((accepted.body as { jobId: unknown }).jobId);
    assert.equal((accepted.body as { status: unknown }).status, "RUNNING");
    const selectedSources = (
      accepted.body as {
        sourceJournalRevisions: { journalId: string }[];
      }
    ).sourceJournalRevisions;
    assert.equal(selectedSources.length, 6);
    assert.equal(
      (
        accepted.body as {
          dataCoverage: { sufficientForComparison: boolean };
        }
      ).dataCoverage.sufficientForComparison,
      true,
    );
    assert.equal(
      selectedSources.some((source) =>
        comparison.excludedJournalIds.includes(source.journalId),
      ),
      false,
    );

    const duplicate = await request(server)
      .post("/api/v1/longitudinal-analysis-jobs")
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "longitudinal-analysis-command-0001")
      .send(comparison)
      .expect(202);
    assert.equal((duplicate.body as { jobId: unknown }).jobId, jobId);

    let completed: request.Response | undefined;
    for (let attempt = 0; attempt < 100; attempt += 1) {
      const response = await request(server)
        .get(`/api/v1/longitudinal-analysis-jobs/${jobId}`)
        .set("Authorization", `Bearer ${ownerToken}`)
        .expect(200);
      if ((response.body as { status?: unknown }).status === "SUCCEEDED") {
        completed = response;
        break;
      }
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    assert.ok(completed);
    const result = (
      completed.body as {
        result?: {
          analysisId?: unknown;
          provider?: unknown;
          dataCoverage?: { sufficientForComparison?: unknown };
        };
      }
    ).result;
    assert.ok(result);
    assert.equal(result.provider, "DETERMINISTIC_FAKE");
    assert.equal(result.dataCoverage?.sufficientForComparison, true);
    const analysisId = String(result.analysisId);

    const care = await request(server)
      .get(`/internal/v1/users/${ownerId}/longitudinal-analyses/${analysisId}`)
      .query({ purpose: "REASSESSMENT_SUMMARY" })
      .set("Authorization", `Bearer ${ownerToken}`)
      .expect(200);
    assert.equal(
      (care.body as { analysisId?: unknown }).analysisId,
      analysisId,
    );
    assert.equal(
      JSON.stringify(care.body).includes("sensitive synthetic"),
      false,
    );

    await request(server)
      .get(`/api/v1/longitudinal-analysis-jobs/${jobId}`)
      .set("Authorization", `Bearer ${otherToken}`)
      .expect(404);

    const storedJob = await database
      .collection<{ _id: string }>("longitudinal_analysis_jobs")
      .findOne({ _id: jobId });
    const storedResult = await database
      .collection<{ jobId: string }>("journal_longitudinal_analysis_results")
      .findOne({ jobId });
    assert.ok(storedJob);
    assert.ok(storedResult);
    const persisted = JSON.stringify({ storedJob, storedResult });
    assert.equal(persisted.includes(ownerToken), false);
    assert.equal(persisted.includes("sensitive synthetic"), false);
    assert.equal(persisted.includes("rawResponse"), false);
    assert.equal(persisted.includes("reasoning"), false);
    assert.ok(forwardedTokens.every((token) => token === ownerToken));

    const sourceToDelete = journalIds[1];
    assert.ok(sourceToDelete);
    await request(server)
      .delete(`/api/v1/journals/${sourceToDelete}`)
      .set("Authorization", `Bearer ${ownerToken}`)
      .set("Idempotency-Key", "longitudinal-source-delete-01")
      .expect(200);
    assert.equal(
      await database
        .collection<{ _id: string }>("longitudinal_analysis_jobs")
        .countDocuments({ _id: jobId }),
      0,
    );
    assert.equal(
      await database
        .collection<{ jobId: string }>("journal_longitudinal_analysis_results")
        .countDocuments({ jobId }),
      0,
    );
  } finally {
    await app.close();
    await client.close();
    await mongo.stop();
  }
});
