import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import test from "node:test";

import {
  ConflictException,
  ForbiddenException,
  ServiceUnavailableException,
} from "@nestjs/common";

import type { ServiceConfiguration } from "../configuration/configuration.js";
import {
  BENCHMARK_RUBRIC_SHA256,
  BENCHMARK_RUBRIC_VERSION,
  type BenchmarkAggregate,
} from "./benchmark.js";
import {
  BenchmarkAdministrationService,
  ConfiguredBenchmarkAdministrationExecutor,
  type BenchmarkAdministrationExecutor,
  type BenchmarkAdministrationStore,
  type GovernedBenchmarkRun,
  type GovernedDataset,
} from "./benchmark-administration.js";

const cases = [
  {
    id: "synthetic-one",
    text: "Tình huống tổng hợp đã được phê duyệt, không phải dữ liệu người dùng.",
    acceptableSuggestedActions: ["NONE" as const],
    requiredSignalTerms: ["tổng hợp"],
    forbiddenClaims: ["chẩn đoán"],
  },
];

const contentSha256 = createHash("sha256")
  .update(JSON.stringify(cases))
  .digest("hex");

const privacyApproval = {
  registry: "MB369_CURATED_REGISTRY" as const,
  approvalId: randomUUID(),
  approvedBy: "privacy-owner",
  approvedAt: "2026-11-16T08:00:00.000Z",
  contentSha256,
};

const datasetBody = {
  datasetId: "governed-synthetic",
  version: "v1",
  workload: "EXACT_REVISION" as const,
  language: "vi",
  source: "MENTALBRIDGE_SYNTHETIC_V1" as const,
  license: "CC0-1.0",
  description: "Synthetic governance fixture",
  cases,
  privacyApproval,
};

const request = {
  headers: {},
  principal: { accountId: randomUUID(), roles: ["ADMIN"] },
};

class MemoryStore implements BenchmarkAdministrationStore {
  readonly approvals = new Map<string, typeof privacyApproval>([
    [privacyApproval.approvalId, privacyApproval],
  ]);
  readonly datasets = new Map<string, GovernedDataset>();
  readonly runs = new Map<string, GovernedBenchmarkRun>();
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

  private datasetKey(datasetId: string, version: string) {
    return `${datasetId}:${version}`;
  }

  findPrivacyApproval(approvalId: string) {
    return Promise.resolve(this.approvals.get(approvalId) ?? null);
  }

  listDatasets() {
    return Promise.resolve([...this.datasets.values()]);
  }

  findDataset(datasetId: string, version: string) {
    return Promise.resolve(
      this.datasets.get(this.datasetKey(datasetId, version)) ?? null,
    );
  }

  insertDataset(dataset: GovernedDataset) {
    const key = this.datasetKey(dataset.datasetId, dataset.version);
    if (this.datasets.has(key))
      return Promise.reject(
        Object.assign(new Error("duplicate"), { code: 11000 }),
      );
    this.datasets.set(key, dataset);
    return Promise.resolve();
  }

  archiveDataset(datasetId: string, version: string, at: Date) {
    const key = this.datasetKey(datasetId, version);
    const dataset = this.datasets.get(key);
    if (!dataset || dataset.archivedAt || dataset.activeRunCount !== 0)
      return Promise.resolve(null);
    const archived = { ...dataset, archivedAt: at };
    this.datasets.set(key, archived);
    return Promise.resolve(archived);
  }

  claimDatasetForRun(datasetId: string, version: string) {
    const key = this.datasetKey(datasetId, version);
    const dataset = this.datasets.get(key);
    if (!dataset || dataset.archivedAt) return Promise.resolve(null);
    const claimed = {
      ...dataset,
      activeRunCount: dataset.activeRunCount + 1,
    };
    this.datasets.set(key, claimed);
    return Promise.resolve(claimed);
  }

  releaseDatasetRun(datasetId: string, version: string) {
    const key = this.datasetKey(datasetId, version);
    const dataset = this.datasets.get(key);
    if (dataset)
      this.datasets.set(key, {
        ...dataset,
        activeRunCount: Math.max(0, dataset.activeRunCount - 1),
      });
    return Promise.resolve();
  }

  insertRun(run: GovernedBenchmarkRun) {
    if (this.runs.has(run._id))
      return Promise.reject(
        Object.assign(new Error("duplicate"), { code: 11000 }),
      );
    this.runs.set(run._id, run);
    return Promise.resolve();
  }

  markRunFailed(runId: string, classification: "EXECUTION_FAILED") {
    const run = this.runs.get(runId);
    if (run)
      this.runs.set(runId, {
        ...run,
        status: "FAILED",
        completedAt: new Date(),
        failureClassification: classification,
      });
    return Promise.resolve();
  }

  listRuns() {
    return Promise.resolve([...this.runs.values()]);
  }

  findRun(runId: string) {
    return Promise.resolve(this.runs.get(runId) ?? null);
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
    if (command)
      this.commands.set(commandId, {
        ...command,
        state: "COMPLETED",
        response,
      });
    return Promise.resolve();
  }

  failCommand(commandId: string) {
    const command = this.commands.get(commandId);
    if (command) this.commands.set(commandId, { ...command, state: "FAILED" });
    return Promise.resolve();
  }
}

class RecordingExecutor implements BenchmarkAdministrationExecutor {
  calls = 0;
  private releaseGate!: () => void;
  private readonly gate = new Promise<void>((resolve) => {
    this.releaseGate = resolve;
  });

  prepare(provider: "GEMINI" | "OPENAI" | "BEDROCK", model: string) {
    return {
      provider,
      model,
      inputCostMicroUsdPerMillionTokens: 100,
      outputCostMicroUsdPerMillionTokens: 200,
    };
  }

  async run(runId: string) {
    this.calls += 1;
    await this.gate;
    return {
      runId,
      status: "COMPLETED" as const,
      failureClassification: null,
      aggregates: [] as BenchmarkAggregate[],
      failures: [],
    };
  }

  release() {
    this.releaseGate();
  }
}

const createService = () => {
  const store = new MemoryStore();
  const executor = new RecordingExecutor();
  return {
    store,
    executor,
    service: new BenchmarkAdministrationService(store, executor),
  };
};

void test("rejects dataset content that is not bound to a curated privacy approval", async () => {
  const { service } = createService();
  await assert.rejects(
    service.create(request, randomUUID(), {
      ...datasetBody,
      privacyApproval: { ...privacyApproval, contentSha256: "0".repeat(64) },
    }),
    ForbiddenException,
  );
});

void test("rejects a self-attested approval that is absent from the curated registry", async () => {
  const { service } = createService();
  await assert.rejects(
    service.create(request, randomUUID(), {
      ...datasetBody,
      privacyApproval: { ...privacyApproval, approvalId: randomUUID() },
    }),
    ForbiddenException,
  );
});

void test("honors the explicit benchmark execution disable gate", () => {
  const executor = new ConfiguredBenchmarkAdministrationExecutor({
    BENCHMARK_ENABLED: false,
  } as ServiceConfiguration);
  assert.throws(
    () => executor.prepare("GEMINI", "gemini-candidate-pinned"),
    ServiceUnavailableException,
  );
});

void test("accepts a slow run immediately, pins the rubric and replays one authoritative job", async () => {
  const { service, store, executor } = createService();
  await service.create(request, randomUUID(), datasetBody);
  const key = randomUUID();
  const body = {
    datasetId: datasetBody.datasetId,
    datasetVersion: datasetBody.version,
    provider: "BEDROCK" as const,
    model: "approved-model",
  };

  const first = await service.run(request, key, body);
  const recoveredCommand = [...store.commands.entries()].find(
    ([, value]) => value.operation === "RUN_BENCHMARK",
  );
  assert.ok(recoveredCommand);
  const [commandId, command] = recoveredCommand;
  store.commands.set(commandId, {
    ...command,
    state: "PENDING",
    response: undefined,
  });
  const replay = await service.run(request, key, body);
  assert.equal(first.status, "RUNNING");
  assert.deepEqual(replay, first);
  assert.equal(executor.calls, 1);
  assert.equal(first.rubricVersion, BENCHMARK_RUBRIC_VERSION);
  assert.equal(first.rubricSha256, BENCHMARK_RUBRIC_SHA256);
  assert.equal(store.runs.size, 1);

  executor.release();
  await new Promise((resolve) => setImmediate(resolve));
});

void test("uses the dataset claim as an archive/start serialization fence", async () => {
  const { service, executor } = createService();
  await service.create(request, randomUUID(), datasetBody);
  await service.run(request, randomUUID(), {
    datasetId: datasetBody.datasetId,
    datasetVersion: datasetBody.version,
    provider: "GEMINI",
    model: "approved-model",
  });

  await assert.rejects(
    service.archive(
      request,
      randomUUID(),
      datasetBody.datasetId,
      datasetBody.version,
    ),
    ConflictException,
  );
  executor.release();
  await new Promise((resolve) => setImmediate(resolve));
});

void test("exports only aggregate evidence with rubric and failure provenance", async () => {
  const { service, store } = createService();
  const runId = randomUUID();
  store.runs.set(runId, {
    _id: runId,
    datasetId: "governed-synthetic",
    datasetVersion: "v1",
    datasetSha256: "a".repeat(64),
    promptVersion: "exact-revision-v1",
    rubricVersion: BENCHMARK_RUBRIC_VERSION,
    rubricSha256: BENCHMARK_RUBRIC_SHA256,
    schemaVersion: 1,
    status: "FAILED",
    candidates: [{ provider: "BEDROCK", model: "approved-model" }],
    aggregates: [],
    startedAt: new Date("2026-11-16T08:00:00.000Z"),
    completedAt: new Date("2026-11-16T08:00:01.000Z"),
    failureClassification: "ALL_CASES_PROVIDER_FAILED",
  });

  const exported = await service.exportRun(request, runId);
  assert.equal(exported.rubricVersion, BENCHMARK_RUBRIC_VERSION);
  assert.equal(exported.failureClassification, "ALL_CASES_PROVIDER_FAILED");
  assert.doesNotMatch(JSON.stringify(exported), /cases|normalizedOutput/u);
});

void test("rejects a changed run payload under the same command key", async () => {
  const { service, executor } = createService();
  await service.create(request, randomUUID(), datasetBody);
  const key = randomUUID();
  await service.run(request, key, {
    datasetId: datasetBody.datasetId,
    datasetVersion: datasetBody.version,
    provider: "GEMINI",
    model: "approved-model",
  });
  await assert.rejects(
    service.run(request, key, {
      datasetId: datasetBody.datasetId,
      datasetVersion: datasetBody.version,
      provider: "OPENAI",
      model: "approved-model",
    }),
    ConflictException,
  );
  executor.release();
});
