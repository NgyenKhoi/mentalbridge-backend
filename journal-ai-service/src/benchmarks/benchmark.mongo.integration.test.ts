import assert from "node:assert/strict";
import test from "node:test";
import { createRequire } from "node:module";

import { MongoClient } from "mongodb";
import { MongoMemoryServer } from "mongodb-memory-server";

import type { ServiceConfiguration } from "../configuration/configuration.js";
import type { ExactRevisionProvider } from "../llm-providers/llm-providers.js";
import { BenchmarkRunner } from "./benchmark.js";

const require = createRequire(import.meta.url);
const migration = require("../../migrations/008_ai_benchmark_metadata.cjs") as {
  up(database: unknown): Promise<void>;
};

void test("persists reproducible benchmark evidence without raw provider responses or source text", async () => {
  const mongo = await MongoMemoryServer.create();
  const databaseName = "benchmark_mb369_integration";
  const client = new MongoClient(mongo.getUri());
  const database = client.db(databaseName);
  await migration.up(database);
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
  } finally {
    await client.close();
    await mongo.stop();
  }
});
