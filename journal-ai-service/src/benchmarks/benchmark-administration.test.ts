import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import test from "node:test";

import { ConflictException, ForbiddenException } from "@nestjs/common";

import {
  BenchmarkAdministrationService,
  type BenchmarkAdministrationExecutor,
  type BenchmarkAdministrationStore,
  type GovernedBenchmarkRun,
  type GovernedDataset,
} from "./benchmark-administration.js";

const datasetBody = (version = "v1") => ({
  datasetId: "synthetic-admin-eval",
  version,
  workload: "EXACT_REVISION" as const,
  language: "vi" as const,
  source: "MENTALBRIDGE_SYNTHETIC_V1" as const,
  license: "CC0-1.0" as const,
  description: "Synthetic Vietnamese evaluation cases",
  cases: [
    {
      id: "ordinary-reflection",
      text: "Tôi muốn sắp xếp lại lịch nghỉ ngơi tuần này.",
      acceptableSuggestedActions: ["NONE" as const],
      requiredSignalTerms: ["nghỉ ngơi"],
      forbiddenClaims: ["chẩn đoán"],
    },
  ],
});

class MemoryStore implements BenchmarkAdministrationStore {
  readonly datasets: GovernedDataset[] = [];
  readonly runs: GovernedBenchmarkRun[] = [];
  readonly commands = new Map<
    string,
    {
      _id: string;
      actorId: string;
      operation: string;
      key: string;
      fingerprint: string;
      state: "PENDING" | "COMPLETED" | "FAILED";
      response?: unknown;
    }
  >();

  listDatasets() {
    return Promise.resolve(this.datasets);
  }

  findDataset(datasetId: string, datasetVersion: string) {
    return Promise.resolve(
      this.datasets.find(
        (item) =>
          item.datasetId === datasetId && item.version === datasetVersion,
      ) ?? null,
    );
  }

  async insertDataset(dataset: GovernedDataset) {
    if (await this.findDataset(dataset.datasetId, dataset.version))
      throw Object.assign(new Error("duplicate"), { code: 11000 });
    this.datasets.push(dataset);
  }

  archiveDataset(datasetId: string, datasetVersion: string, at: Date) {
    const index = this.datasets.findIndex(
      (item) => item.datasetId === datasetId && item.version === datasetVersion,
    );
    if (index < 0) return Promise.resolve(null);
    const current = this.datasets[index];
    if (!current) return Promise.resolve(null);
    const archived = { ...current, archivedAt: at };
    this.datasets[index] = archived;
    return Promise.resolve(archived);
  }

  listRuns() {
    return Promise.resolve(this.runs);
  }

  findRun(runId: string) {
    return Promise.resolve(this.runs.find((run) => run._id === runId) ?? null);
  }

  reserveCommand(
    command: Parameters<BenchmarkAdministrationStore["reserveCommand"]>[0],
  ) {
    const existing = this.commands.get(command._id);
    if (existing) return Promise.resolve(existing);
    this.commands.set(command._id, command);
    return Promise.resolve(null);
  }

  completeCommand(commandId: string, response: unknown) {
    const command = this.commands.get(commandId);
    assert.ok(command);
    this.commands.set(commandId, { ...command, state: "COMPLETED", response });
    return Promise.resolve();
  }

  failCommand(commandId: string) {
    const command = this.commands.get(commandId);
    assert.ok(command);
    this.commands.set(commandId, { ...command, state: "FAILED" });
    return Promise.resolve();
  }
}

class RecordingExecutor implements BenchmarkAdministrationExecutor {
  calls = 0;

  run(
    dataset: GovernedDataset,
    provider: "GEMINI" | "OPENAI" | "BEDROCK",
    model: string,
  ) {
    this.calls += 1;
    return Promise.resolve({
      runId: randomUUID(),
      aggregates: [
        {
          provider,
          model,
          caseCount: dataset.caseCount,
          successCount: dataset.caseCount,
          errorCount: 0,
          qualityPassRate: 1,
          safetyPassRate: 1,
          averageQualityScore: 1,
          averageLatencyMs: 10,
          totalInputTokens: 5,
          totalOutputTokens: 5,
          totalEstimatedCostMicroUsd: 1,
        },
      ],
      failures: [],
    });
  }
}

const adminRequest = {
  headers: {},
  principal: { accountId: randomUUID(), roles: ["ADMIN"] },
};

void test("requires approved ADMIN authority", async () => {
  const service = new BenchmarkAdministrationService(
    new MemoryStore(),
    new RecordingExecutor(),
  );
  await assert.rejects(
    () =>
      service.list({
        headers: {},
        principal: { accountId: randomUUID(), roles: ["USER"] },
      }),
    ForbiddenException,
  );
});

void test("creates immutable versions and replays the exact command", async () => {
  const store = new MemoryStore();
  const service = new BenchmarkAdministrationService(
    store,
    new RecordingExecutor(),
  );
  const key = randomUUID();
  const created = await service.create(adminRequest, key, datasetBody());
  const replay = await service.create(adminRequest, key, datasetBody());
  assert.deepEqual(replay, created);
  assert.equal(store.datasets.length, 1);

  await assert.rejects(
    () => service.create(adminRequest, key, datasetBody("v2")),
    (error: unknown) =>
      error instanceof ConflictException &&
      error.message.includes("IDEMPOTENCY_CONFLICT"),
  );

  await service.createVersion(
    adminRequest,
    randomUUID(),
    "synthetic-admin-eval",
    "v1",
    {
      version: "v2",
      description: "A second immutable synthetic version",
      cases: datasetBody().cases,
    },
  );
  assert.equal(store.datasets.length, 2);
  assert.equal(store.datasets[0]?.version, "v1");
  assert.equal(store.datasets[1]?.previousVersion, "v1");
});

void test("archives a dataset without hiding audit history and blocks new runs", async () => {
  const store = new MemoryStore();
  const executor = new RecordingExecutor();
  const service = new BenchmarkAdministrationService(store, executor);
  await service.create(adminRequest, randomUUID(), datasetBody());
  await service.archive(
    adminRequest,
    randomUUID(),
    "synthetic-admin-eval",
    "v1",
  );
  const listed = await service.list(adminRequest);
  assert.equal(listed.data.length, 1);
  assert.notEqual(listed.data[0]?.archivedAt, null);
  await assert.rejects(
    () =>
      service.run(adminRequest, randomUUID(), {
        datasetId: "synthetic-admin-eval",
        datasetVersion: "v1",
        provider: "GEMINI",
        model: "gemini-pinned-v1",
      }),
    ConflictException,
  );
  assert.equal(executor.calls, 0);
});

void test("runs one pinned dataset and model with idempotent replay", async () => {
  const store = new MemoryStore();
  const executor = new RecordingExecutor();
  const service = new BenchmarkAdministrationService(store, executor);
  await service.create(adminRequest, randomUUID(), datasetBody());
  const key = randomUUID();
  const intent = {
    datasetId: "synthetic-admin-eval",
    datasetVersion: "v1",
    provider: "OPENAI",
    model: "gpt-pinned-v1",
  };
  const first = await service.run(adminRequest, key, intent);
  const replay = await service.run(adminRequest, key, intent);
  assert.deepEqual(replay, first);
  assert.equal(executor.calls, 1);
});

void test("exports aggregate provenance without dataset cases or provider output", async () => {
  const store = new MemoryStore();
  const service = new BenchmarkAdministrationService(
    store,
    new RecordingExecutor(),
  );
  const runId = randomUUID();
  store.runs.push({
    _id: runId,
    datasetId: "synthetic-admin-eval",
    datasetVersion: "v1",
    datasetSha256: "a".repeat(64),
    promptVersion: "exact-revision-v1",
    schemaVersion: 1,
    status: "COMPLETED",
    candidates: [{ provider: "GEMINI", model: "gemini-pinned-v1" }],
    aggregates: [],
    startedAt: new Date("2026-11-16T00:00:00.000Z"),
    completedAt: new Date("2026-11-16T00:01:00.000Z"),
  });
  const exported = await service.exportRun(adminRequest, runId);
  const serialized = JSON.stringify(exported);
  assert.equal(exported.exportVersion, "benchmark-aggregate-v1");
  assert.equal(serialized.includes("cases"), false);
  assert.equal(serialized.includes("normalizedOutput"), false);
});
