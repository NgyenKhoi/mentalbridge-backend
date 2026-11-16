import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import test from "node:test";
import { createRequire } from "node:module";

import { MongoClient } from "mongodb";
import { MongoMemoryServer } from "mongodb-memory-server";

import type { ServiceConfiguration } from "../configuration/configuration.js";
import type { ExactRevisionProvider } from "../llm-providers/llm-providers.js";
import {
  BENCHMARK_RUBRIC_SHA256,
  BENCHMARK_RUBRIC_VERSION,
  BenchmarkRunner,
} from "./benchmark.js";
import {
  BenchmarkAdministrationService,
  MongoBenchmarkAdministrationStore,
  type BenchmarkAdministrationExecutor,
} from "./benchmark-administration.js";

const require = createRequire(import.meta.url);
const migration = require("../../migrations/008_ai_benchmark_metadata.cjs") as {
  up(database: unknown): Promise<void>;
};
const governanceMigration =
  require("../../migrations/019_benchmark_dataset_governance.cjs") as {
    up(database: unknown): Promise<void>;
  };
const executionMigration =
  require("../../migrations/020_benchmark_execution_governance.cjs") as {
    up(database: unknown): Promise<void>;
  };

void test("persists reproducible benchmark evidence without raw provider responses or source text", async () => {
  const mongo = await MongoMemoryServer.create();
  const databaseName = "benchmark_mb369_integration";
  const client = new MongoClient(mongo.getUri());
  const database = client.db(databaseName);
  await migration.up(database);
  await governanceMigration.up(database);
  await executionMigration.up(database);
  const configuration = {
    MONGODB_URI: mongo.getUri(),
    MONGODB_DATABASE: databaseName,
    MONGODB_CONNECTION_TIMEOUT_MS: 2_000,
    BENCHMARK_ENABLED: true,
    BENCHMARK_DATASET_PATH:
      "benchmarks/datasets/exact-revision-synthetic-v1.json",
    BENCHMARK_GEMINI_ROUTE: {
      provider: "GEMINI",
      model: "gemini-candidate-pinned",
      inputCostMicroUsdPerMillionTokens: 100,
      outputCostMicroUsdPerMillionTokens: 200,
    },
    BENCHMARK_OPENAI_ROUTE: null,
  } as ServiceConfiguration;
  const provider: ExactRevisionProvider = {
    analyze: (_text, route) =>
      Promise.resolve({
        output: {
          summary: "Phản ánh tổng hợp từ dữ liệu giả lập.",
          contextSignals: ["deadline", "công việc"],
          emotionIndicators: ["căng thẳng"],
          themes: ["sinh hoạt"],
          preferenceSignals: [],
          barrierSignals: [],
          suggestedAction: "NONE",
        },
        latencyMs: route.provider === "GEMINI" ? 10 : 20,
        usage: {
          inputTokens: 10,
          outputTokens: 5,
          estimatedCostMicroUsd: 1,
        },
      }),
  };

  try {
    const result = await new BenchmarkRunner(configuration, provider).run();
    assert.equal(result.aggregates.length, 1);
    assert.equal(
      await database.collection("benchmark_case_results").countDocuments({
        runId: result.runId,
      }),
      6,
    );
    const run = await database
      .collection<{
        _id: string;
        status: string;
        candidates: { inputCostMicroUsdPerMillionTokens: number }[];
      }>("benchmark_runs")
      .findOne({
        _id: result.runId,
      });
    if (!run) throw new Error("Expected persisted benchmark run");
    assert.equal(run.status, "COMPLETED");
    assert.equal(run.candidates[0]?.inputCostMicroUsdPerMillionTokens, 100);
    const persisted = JSON.stringify({
      datasets: await database
        .collection("benchmark_datasets")
        .find()
        .toArray(),
      runs: await database.collection("benchmark_runs").find().toArray(),
      results: await database
        .collection("benchmark_case_results")
        .find()
        .toArray(),
    });
    assert.doesNotMatch(persisted, /Hôm nay tôi đi làm đúng giờ/);
    assert.doesNotMatch(persisted, /rawResponse|chainOfThought/);

    const unavailableProvider: ExactRevisionProvider = {
      analyze: () => Promise.reject(new Error("provider unavailable")),
    };
    const failed = await new BenchmarkRunner(
      configuration,
      unavailableProvider,
      () => Promise.resolve(),
    ).run();
    assert.equal(failed.status, "FAILED");
    assert.equal(failed.failureClassification, "ALL_CASES_PROVIDER_FAILED");
    assert.ok(
      failed.aggregates.every(({ successCount }) => successCount === 0),
    );
    const failedRun = await database
      .collection<Record<string, unknown> & { _id: string }>("benchmark_runs")
      .findOne({ _id: failed.runId });
    assert.ok(failedRun);
    assert.equal(failedRun.status, "FAILED");
    assert.equal(failedRun.failureClassification, "ALL_CASES_PROVIDER_FAILED");
  } finally {
    await client.close();
    await mongo.stop();
  }
});

void test("reserves concurrent admin commands and persists immutable governed versions", async () => {
  const mongo = await MongoMemoryServer.create();
  const databaseName = "benchmark_mb697_integration";
  const client = new MongoClient(mongo.getUri());
  const database = client.db(databaseName);
  await migration.up(database);
  await governanceMigration.up(database);
  await executionMigration.up(database);
  const configuration = {
    MONGODB_URI: mongo.getUri(),
    MONGODB_DATABASE: databaseName,
    MONGODB_CONNECTION_TIMEOUT_MS: 2_000,
  } as ServiceConfiguration;
  const executor: BenchmarkAdministrationExecutor = {
    prepare: (provider, model) => ({
      provider,
      model,
      inputCostMicroUsdPerMillionTokens: 100,
      outputCostMicroUsdPerMillionTokens: 200,
    }),
    run: () => Promise.reject(new Error("not used")),
  };
  const store = new MongoBenchmarkAdministrationStore(configuration);
  const service = new BenchmarkAdministrationService(store, executor);
  const request = {
    headers: {},
    principal: { accountId: randomUUID(), roles: ["ADMIN"] },
  };
  const key = randomUUID();
  const body = {
    datasetId: "governed-synthetic",
    version: "v1",
    workload: "EXACT_REVISION",
    language: "vi",
    source: "MENTALBRIDGE_SYNTHETIC_V1",
    license: "CC0-1.0",
    description: "Synthetic governance integration fixture",
    cases: [
      {
        id: "case-one",
        text: "Tình huống tổng hợp không thuộc người dùng thật.",
        acceptableSuggestedActions: ["NONE"],
        requiredSignalTerms: ["tổng hợp"],
        forbiddenClaims: ["chẩn đoán"],
      },
    ],
  };
  const approvedBody = {
    ...body,
    privacyApproval: {
      registry: "MB369_CURATED_REGISTRY",
      approvalId: randomUUID(),
      approvedBy: "privacy-owner",
      approvedAt: "2026-11-16T08:00:00.000Z",
      contentSha256: createHash("sha256")
        .update(JSON.stringify(body.cases))
        .digest("hex"),
    },
  };
  await database
    .collection<Record<string, unknown> & { _id: string }>(
      "benchmark_dataset_approvals",
    )
    .insertOne({
      _id: approvedBody.privacyApproval.approvalId,
      ...approvedBody.privacyApproval,
    });

  try {
    const [first, conflicting] = await Promise.allSettled([
      service.create(request, key, approvedBody),
      service.create(request, key, {
        ...approvedBody,
        description: "Changed intent",
      }),
    ]);
    assert.equal(
      [first, conflicting].filter((result) => result.status === "fulfilled")
        .length,
      1,
    );
    assert.equal(
      await database.collection("benchmark_datasets").countDocuments(),
      1,
    );
    assert.equal(
      await database.collection("benchmark_admin_commands").countDocuments(),
      1,
    );
    const stored = await database
      .collection("benchmark_datasets")
      .findOne({ datasetId: "governed-synthetic", version: "v1" });
    assert.ok(stored);
    const storedDataset = stored as unknown as {
      source: unknown;
      archivedAt: unknown;
      cases: unknown;
    };
    assert.equal(storedDataset.source, "MENTALBRIDGE_SYNTHETIC_V1");
    assert.equal(storedDataset.archivedAt, null);
    const storedCases = storedDataset.cases;
    assert.ok(Array.isArray(storedCases));
    assert.equal(storedCases.length, 1);

    const bedrockRunId = randomUUID();
    await database
      .collection<Record<string, unknown> & { _id: string }>("benchmark_runs")
      .insertOne({
        _id: bedrockRunId,
        datasetId: body.datasetId,
        datasetVersion: body.version,
        datasetSha256: "a".repeat(64),
        promptVersion: "exact-revision-v1",
        rubricVersion: BENCHMARK_RUBRIC_VERSION,
        rubricSha256: BENCHMARK_RUBRIC_SHA256,
        schemaVersion: 1,
        status: "RUNNING",
        candidates: [
          {
            provider: "BEDROCK",
            model: "anthropic.claude-approved",
            inputCostMicroUsdPerMillionTokens: 100,
            outputCostMicroUsdPerMillionTokens: 200,
          },
        ],
        startedAt: new Date(),
        completedAt: null,
        failureClassification: null,
      });
    await database
      .collection<Record<string, unknown> & { _id: string; runId: string }>(
        "benchmark_case_results",
      )
      .insertOne({
        _id: randomUUID(),
        runId: bedrockRunId,
        caseId: "case-one",
        provider: "BEDROCK",
        model: "anthropic.claude-approved",
        promptVersion: "exact-revision-v1",
        rubricVersion: BENCHMARK_RUBRIC_VERSION,
        rubricSha256: BENCHMARK_RUBRIC_SHA256,
        schemaVersion: 1,
        status: "FAILED",
        errorClassification: "PROVIDER_UNAVAILABLE",
        qualityScore: null,
        qualityPassed: null,
        safetyPassed: null,
        matchedSignalTerms: null,
        requiredSignalTerms: 1,
        latencyMs: null,
        inputTokens: null,
        outputTokens: null,
        estimatedCostMicroUsd: null,
        normalizedOutput: null,
        createdAt: new Date(),
      });
    assert.equal(
      await database
        .collection("benchmark_case_results")
        .countDocuments({ runId: bedrockRunId, provider: "BEDROCK" }),
      1,
    );
  } finally {
    await store.onModuleDestroy();
    await client.close();
    await mongo.stop();
  }
});
