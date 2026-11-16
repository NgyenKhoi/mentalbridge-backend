import { createHash } from "node:crypto";

import {
  BadRequestException,
  Body,
  ConflictException,
  Controller,
  ForbiddenException,
  Get,
  Headers,
  HttpCode,
  Injectable,
  Module,
  NotFoundException,
  Param,
  Post,
  Req,
  ServiceUnavailableException,
  UnprocessableEntityException,
  type DynamicModule,
  type OnModuleDestroy,
  type Provider,
} from "@nestjs/common";
import { MongoClient, type Collection } from "mongodb";
import { z } from "zod";

import type { ServiceConfiguration } from "../configuration/configuration.js";
import type { AuthenticatedRequest } from "../security/authenticated-principal.js";
import {
  BenchmarkRunner,
  BENCHMARK_RUBRIC_SHA256,
  BENCHMARK_RUBRIC_VERSION,
  benchmarkCaseSchema,
  benchmarkDatasetSchema,
  type BenchmarkAggregate,
  type BenchmarkDataset,
  type BenchmarkFailureDiagnostic,
} from "./benchmark.js";

const STORE = Symbol("BENCHMARK_ADMINISTRATION_STORE");
const EXECUTOR = Symbol("BENCHMARK_ADMINISTRATION_EXECUTOR");

const identifier = z.string().regex(/^[A-Za-z0-9._-]{1,96}$/);
const version = z.string().regex(/^[A-Za-z0-9._-]{1,32}$/);
const idempotencyKey = z.uuid();
const privacyApprovalSchema = z
  .object({
    registry: z.literal("MB369_CURATED_REGISTRY"),
    approvalId: z.uuid(),
    approvedBy: z.string().trim().min(1).max(128),
    approvedAt: z.iso.datetime(),
    contentSha256: z.string().regex(/^[0-9a-f]{64}$/),
  })
  .strict();
const createDatasetSchema = benchmarkDatasetSchema.extend({
  privacyApproval: privacyApprovalSchema,
});
const createVersionSchema = z
  .object({
    version,
    description: z.string().trim().min(1).max(300),
    cases: z.array(benchmarkCaseSchema).min(1).max(200),
    privacyApproval: privacyApprovalSchema,
  })
  .strict();
const runSchema = z
  .object({
    datasetId: identifier,
    datasetVersion: version,
    provider: z.enum(["GEMINI", "OPENAI", "BEDROCK"]),
    model: z.string().trim().min(1).max(128),
  })
  .strict();

interface AdministrationRequest extends AuthenticatedRequest {
  readonly body?: unknown;
}

export interface GovernedDataset extends BenchmarkDataset {
  readonly sha256: string;
  readonly caseCount: number;
  readonly registeredAt: Date;
  readonly createdBy: string;
  readonly archivedAt: Date | null;
  readonly previousVersion: string | null;
  readonly activeRunCount: number;
  readonly privacyApproval: z.infer<typeof privacyApprovalSchema>;
}

interface DatasetDocument extends GovernedDataset {
  readonly _id: string;
}

type BenchmarkPrivacyApproval = z.infer<typeof privacyApprovalSchema>;

interface PrivacyApprovalDocument extends BenchmarkPrivacyApproval {
  readonly _id: string;
}

export interface GovernedBenchmarkRun {
  readonly _id: string;
  readonly datasetId: string;
  readonly datasetVersion: string;
  readonly datasetSha256: string;
  readonly promptVersion: string;
  readonly rubricVersion: string;
  readonly rubricSha256: string;
  readonly schemaVersion: 1;
  readonly status: "RUNNING" | "COMPLETED" | "FAILED";
  readonly candidates: readonly {
    provider: "GEMINI" | "OPENAI" | "BEDROCK";
    model: string;
  }[];
  readonly aggregates?: readonly BenchmarkAggregate[];
  readonly startedAt: Date;
  readonly completedAt: Date | null;
  readonly failureClassification:
    "ALL_CASES_PROVIDER_FAILED" | "EXECUTION_FAILED" | null;
}

interface CommandDocument {
  readonly _id: string;
  readonly actorId: string;
  readonly operation: string;
  readonly key: string;
  readonly fingerprint: string;
  readonly state: "PENDING" | "COMPLETED" | "FAILED";
  readonly response?: unknown;
}

export interface BenchmarkAdministrationStore {
  findPrivacyApproval(
    approvalId: string,
  ): Promise<BenchmarkPrivacyApproval | null>;
  listDatasets(): Promise<GovernedDataset[]>;
  findDataset(
    datasetId: string,
    datasetVersion: string,
  ): Promise<GovernedDataset | null>;
  insertDataset(dataset: GovernedDataset): Promise<void>;
  archiveDataset(
    datasetId: string,
    datasetVersion: string,
    at: Date,
  ): Promise<GovernedDataset | null>;
  claimDatasetForRun(
    datasetId: string,
    datasetVersion: string,
  ): Promise<GovernedDataset | null>;
  releaseDatasetRun(datasetId: string, datasetVersion: string): Promise<void>;
  insertRun(run: GovernedBenchmarkRun): Promise<void>;
  markRunFailed(
    runId: string,
    classification: "EXECUTION_FAILED",
  ): Promise<void>;
  listRuns(): Promise<GovernedBenchmarkRun[]>;
  findRun(runId: string): Promise<GovernedBenchmarkRun | null>;
  reserveCommand(command: CommandDocument): Promise<CommandDocument | null>;
  completeCommand(commandId: string, response: unknown): Promise<void>;
  failCommand(commandId: string): Promise<void>;
}

export interface BenchmarkAdministrationExecutor {
  prepare(
    provider: "GEMINI" | "OPENAI" | "BEDROCK",
    model: string,
  ): {
    provider: "GEMINI" | "OPENAI" | "BEDROCK";
    model: string;
    inputCostMicroUsdPerMillionTokens: number;
    outputCostMicroUsdPerMillionTokens: number;
  };
  run(
    runId: string,
    dataset: GovernedDataset,
    provider: "GEMINI" | "OPENAI" | "BEDROCK",
    model: string,
  ): Promise<{
    runId: string;
    status: "COMPLETED" | "FAILED";
    failureClassification:
      "ALL_CASES_PROVIDER_FAILED" | "EXECUTION_FAILED" | null;
    aggregates: BenchmarkAggregate[];
    failures: BenchmarkFailureDiagnostic[];
  }>;
}

const datasetResponse = (dataset: GovernedDataset) => ({
  datasetId: dataset.datasetId,
  version: dataset.version,
  workload: dataset.workload,
  language: dataset.language,
  source: dataset.source,
  license: dataset.license,
  description: dataset.description,
  sha256: dataset.sha256,
  caseCount: dataset.caseCount,
  registeredAt: dataset.registeredAt.toISOString(),
  archivedAt: dataset.archivedAt?.toISOString() ?? null,
  previousVersion: dataset.previousVersion,
  privacyApproval: dataset.privacyApproval,
});

const fingerprint = (value: unknown): string =>
  createHash("sha256").update(JSON.stringify(value)).digest("hex");

const deterministicRunId = (actorId: string, key: string): string => {
  const value = createHash("sha256")
    .update(`${actorId}:RUN_BENCHMARK:${key}`)
    .digest("hex")
    .slice(0, 32)
    .split("");
  value[12] = "4";
  value[16] = "8";
  const hex = value.join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
};

@Injectable()
export class MongoBenchmarkAdministrationStore
  implements BenchmarkAdministrationStore, OnModuleDestroy
{
  private readonly client: MongoClient;
  private readonly datasets: Collection<DatasetDocument>;
  private readonly approvals: Collection<PrivacyApprovalDocument>;
  private readonly runs: Collection<GovernedBenchmarkRun>;
  private readonly commands: Collection<CommandDocument>;

  constructor(configuration: ServiceConfiguration) {
    this.client = new MongoClient(configuration.MONGODB_URI, {
      serverSelectionTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
    });
    const database = this.client.db(configuration.MONGODB_DATABASE);
    this.datasets = database.collection("benchmark_datasets");
    this.approvals = database.collection("benchmark_dataset_approvals");
    this.runs = database.collection("benchmark_runs");
    this.commands = database.collection("benchmark_admin_commands");
  }

  async onModuleDestroy(): Promise<void> {
    await this.client.close();
  }

  async findPrivacyApproval(
    approvalId: string,
  ): Promise<BenchmarkPrivacyApproval | null> {
    const document = await this.approvals.findOne({ _id: approvalId });
    if (!document) return null;
    return {
      registry: document.registry,
      approvalId: document.approvalId,
      approvedBy: document.approvedBy,
      approvedAt: document.approvedAt,
      contentSha256: document.contentSha256,
    };
  }

  async listDatasets(): Promise<GovernedDataset[]> {
    return this.datasets
      .find({
        createdBy: { $exists: true },
        "privacyApproval.registry": "MB369_CURATED_REGISTRY",
      })
      .sort({ datasetId: 1, registeredAt: -1 })
      .limit(100)
      .toArray();
  }

  async findDataset(
    datasetId: string,
    datasetVersion: string,
  ): Promise<GovernedDataset | null> {
    return this.datasets.findOne({
      datasetId,
      version: datasetVersion,
      "privacyApproval.registry": "MB369_CURATED_REGISTRY",
    });
  }

  async insertDataset(dataset: GovernedDataset): Promise<void> {
    await this.datasets.insertOne({
      _id: `${dataset.datasetId}:${dataset.version}`,
      ...dataset,
    });
  }

  async archiveDataset(
    datasetId: string,
    datasetVersion: string,
    at: Date,
  ): Promise<GovernedDataset | null> {
    return this.datasets.findOneAndUpdate(
      {
        datasetId,
        version: datasetVersion,
        archivedAt: null,
        activeRunCount: 0,
      },
      { $set: { archivedAt: at } },
      { returnDocument: "after" },
    );
  }

  async claimDatasetForRun(
    datasetId: string,
    datasetVersion: string,
  ): Promise<GovernedDataset | null> {
    return this.datasets.findOneAndUpdate(
      {
        datasetId,
        version: datasetVersion,
        archivedAt: null,
        "privacyApproval.registry": "MB369_CURATED_REGISTRY",
      },
      { $inc: { activeRunCount: 1 } },
      { returnDocument: "after" },
    );
  }

  async releaseDatasetRun(
    datasetId: string,
    datasetVersion: string,
  ): Promise<void> {
    await this.datasets.updateOne(
      { datasetId, version: datasetVersion, activeRunCount: { $gt: 0 } },
      { $inc: { activeRunCount: -1 } },
    );
  }

  async insertRun(run: GovernedBenchmarkRun): Promise<void> {
    await this.runs.insertOne(run);
  }

  async markRunFailed(
    runId: string,
    classification: "EXECUTION_FAILED",
  ): Promise<void> {
    await this.runs.updateOne(
      { _id: runId, status: "RUNNING" },
      {
        $set: {
          status: "FAILED",
          completedAt: new Date(),
          failureClassification: classification,
        },
      },
    );
  }

  async listRuns(): Promise<GovernedBenchmarkRun[]> {
    return this.runs.find().sort({ startedAt: -1 }).limit(100).toArray();
  }

  async findRun(runId: string): Promise<GovernedBenchmarkRun | null> {
    return this.runs.findOne({ _id: runId });
  }

  async reserveCommand(
    command: CommandDocument,
  ): Promise<CommandDocument | null> {
    try {
      await this.commands.insertOne(command);
      return null;
    } catch (error) {
      if (
        typeof error === "object" &&
        error !== null &&
        "code" in error &&
        error.code === 11000
      )
        return this.commands.findOne({ _id: command._id });
      throw error;
    }
  }

  async completeCommand(commandId: string, response: unknown): Promise<void> {
    await this.commands.updateOne(
      { _id: commandId, state: "PENDING" },
      { $set: { state: "COMPLETED", response } },
    );
  }

  async failCommand(commandId: string): Promise<void> {
    await this.commands.updateOne(
      { _id: commandId, state: "PENDING" },
      { $set: { state: "FAILED" } },
    );
  }
}

@Injectable()
export class ConfiguredBenchmarkAdministrationExecutor implements BenchmarkAdministrationExecutor {
  constructor(private readonly configuration: ServiceConfiguration) {}

  prepare(provider: "GEMINI" | "OPENAI" | "BEDROCK", model: string) {
    if (!this.configuration.BENCHMARK_ENABLED)
      throw new ServiceUnavailableException("Benchmark execution is disabled");
    const gemini =
      provider === "GEMINI" ? this.configuration.BENCHMARK_GEMINI_ROUTE : null;
    const openAi =
      provider === "OPENAI" ? this.configuration.BENCHMARK_OPENAI_ROUTE : null;
    const bedrock =
      provider === "BEDROCK"
        ? this.configuration.BENCHMARK_BEDROCK_ROUTE
        : null;
    const selected = gemini ?? openAi ?? bedrock;
    if (selected?.model !== model)
      throw new UnprocessableEntityException(
        "The requested provider model is not an approved benchmark candidate",
      );
    return { ...selected, provider };
  }

  async run(
    runId: string,
    dataset: GovernedDataset,
    provider: "GEMINI" | "OPENAI" | "BEDROCK",
    model: string,
  ) {
    this.prepare(provider, model);
    const gemini =
      provider === "GEMINI" ? this.configuration.BENCHMARK_GEMINI_ROUTE : null;
    const openAi =
      provider === "OPENAI" ? this.configuration.BENCHMARK_OPENAI_ROUTE : null;
    const bedrock =
      provider === "BEDROCK"
        ? this.configuration.BENCHMARK_BEDROCK_ROUTE
        : null;
    const scopedConfiguration: ServiceConfiguration = {
      ...this.configuration,
      BENCHMARK_GEMINI_ROUTE: gemini,
      BENCHMARK_OPENAI_ROUTE: openAi,
      BENCHMARK_BEDROCK_ROUTE: bedrock,
    };
    return new BenchmarkRunner(
      scopedConfiguration,
      undefined,
      undefined,
      () =>
        Promise.resolve({
          dataset,
          sha256: dataset.sha256,
        }),
      { runId, precreated: true },
    ).run();
  }
}

@Injectable()
export class BenchmarkAdministrationService {
  constructor(
    private readonly store: BenchmarkAdministrationStore,
    private readonly executor: BenchmarkAdministrationExecutor,
  ) {}

  async list(request: AdministrationRequest) {
    this.assertAdmin(request);
    return { data: (await this.store.listDatasets()).map(datasetResponse) };
  }

  async inspect(
    request: AdministrationRequest,
    datasetId: string,
    datasetVersion: string,
  ) {
    this.assertAdmin(request);
    const dataset = await this.requiredDataset(datasetId, datasetVersion);
    return { ...datasetResponse(dataset), cases: dataset.cases };
  }

  async create(
    request: AdministrationRequest,
    key: string | undefined,
    body: unknown,
  ) {
    const actorId = this.assertAdmin(request);
    const parsed = this.parse(createDatasetSchema, body);
    await this.assertPrivacyApproval(parsed.cases, parsed.privacyApproval);
    return this.command(actorId, "CREATE_DATASET", key, parsed, async () => {
      const sha256 = fingerprint(parsed);
      const dataset: GovernedDataset = {
        ...parsed,
        sha256,
        caseCount: parsed.cases.length,
        registeredAt: new Date(),
        createdBy: actorId,
        archivedAt: null,
        previousVersion: null,
        activeRunCount: 0,
        privacyApproval: parsed.privacyApproval,
      };
      try {
        await this.store.insertDataset(dataset);
      } catch (error) {
        if (
          typeof error === "object" &&
          error !== null &&
          "code" in error &&
          error.code === 11000
        )
          throw new ConflictException("Dataset version already exists");
        throw error;
      }
      return datasetResponse(dataset);
    });
  }

  async createVersion(
    request: AdministrationRequest,
    key: string | undefined,
    datasetId: string,
    sourceVersion: string,
    body: unknown,
  ) {
    const actorId = this.assertAdmin(request);
    const parsed = this.parse(createVersionSchema, body);
    await this.assertPrivacyApproval(parsed.cases, parsed.privacyApproval);
    const intent = { datasetId, sourceVersion, ...parsed };
    return this.command(
      actorId,
      "CREATE_DATASET_VERSION",
      key,
      intent,
      async () => {
        const source = await this.requiredDataset(datasetId, sourceVersion);
        if (parsed.version === sourceVersion)
          throw new ConflictException("A new dataset version is required");
        const candidate = benchmarkDatasetSchema.parse({
          datasetId,
          version: parsed.version,
          workload: source.workload,
          language: source.language,
          source: source.source,
          license: source.license,
          description: parsed.description,
          cases: parsed.cases,
        });
        const dataset: GovernedDataset = {
          ...candidate,
          sha256: fingerprint(candidate),
          caseCount: candidate.cases.length,
          registeredAt: new Date(),
          createdBy: actorId,
          archivedAt: null,
          previousVersion: sourceVersion,
          activeRunCount: 0,
          privacyApproval: parsed.privacyApproval,
        };
        try {
          await this.store.insertDataset(dataset);
        } catch (error) {
          if (
            typeof error === "object" &&
            error !== null &&
            "code" in error &&
            error.code === 11000
          )
            throw new ConflictException("Dataset version already exists");
          throw error;
        }
        return datasetResponse(dataset);
      },
    );
  }

  async archive(
    request: AdministrationRequest,
    key: string | undefined,
    datasetId: string,
    datasetVersion: string,
  ) {
    const actorId = this.assertAdmin(request);
    const intent = { datasetId, datasetVersion };
    return this.command(actorId, "ARCHIVE_DATASET", key, intent, async () => {
      const current = await this.requiredDataset(datasetId, datasetVersion);
      if (current.archivedAt) return datasetResponse(current);
      const archived = await this.store.archiveDataset(
        datasetId,
        datasetVersion,
        new Date(),
      );
      if (!archived)
        throw new ConflictException(
          "Dataset has an active benchmark selection and cannot be archived",
        );
      return datasetResponse(archived);
    });
  }

  async run(
    request: AdministrationRequest,
    key: string | undefined,
    body: unknown,
  ) {
    const actorId = this.assertAdmin(request);
    const parsed = this.parse(runSchema, body);
    const parsedKey = this.parse(idempotencyKey, key);
    const candidate = this.executor.prepare(parsed.provider, parsed.model);
    const runId = deterministicRunId(actorId, parsedKey);
    return this.command(
      actorId,
      "RUN_BENCHMARK",
      parsedKey,
      parsed,
      async () => {
        const dataset = await this.store.claimDatasetForRun(
          parsed.datasetId,
          parsed.datasetVersion,
        );
        if (!dataset) {
          const current = await this.requiredDataset(
            parsed.datasetId,
            parsed.datasetVersion,
          );
          if (current.archivedAt)
            throw new ConflictException("Archived datasets cannot be selected");
          throw new ConflictException("Dataset selection is unavailable");
        }
        const run: GovernedBenchmarkRun = {
          _id: runId,
          datasetId: dataset.datasetId,
          datasetVersion: dataset.version,
          datasetSha256: dataset.sha256,
          promptVersion: "exact-revision-v1",
          rubricVersion: BENCHMARK_RUBRIC_VERSION,
          rubricSha256: BENCHMARK_RUBRIC_SHA256,
          schemaVersion: 1,
          status: "RUNNING",
          candidates: [candidate],
          startedAt: new Date(),
          completedAt: null,
          failureClassification: null,
        };
        try {
          await this.store.insertRun(run);
        } catch (error) {
          await this.store.releaseDatasetRun(
            dataset.datasetId,
            dataset.version,
          );
          if (
            typeof error === "object" &&
            error !== null &&
            "code" in error &&
            error.code === 11000
          ) {
            const existing = await this.store.findRun(runId);
            if (existing) return this.runResponse(existing);
          }
          throw error;
        }
        void this.executeAcceptedRun(
          runId,
          dataset,
          parsed.provider,
          parsed.model,
        );
        return this.runResponse(run);
      },
      async () => {
        const existing = await this.store.findRun(runId);
        return existing ? this.runResponse(existing) : null;
      },
    );
  }

  private async executeAcceptedRun(
    runId: string,
    dataset: GovernedDataset,
    provider: "GEMINI" | "OPENAI" | "BEDROCK",
    model: string,
  ): Promise<void> {
    try {
      await this.executor.run(runId, dataset, provider, model);
    } catch {
      await this.store.markRunFailed(runId, "EXECUTION_FAILED");
    } finally {
      await this.store.releaseDatasetRun(dataset.datasetId, dataset.version);
    }
  }

  async listRuns(request: AdministrationRequest) {
    this.assertAdmin(request);
    return { data: (await this.store.listRuns()).map(this.runResponse) };
  }

  async inspectRun(request: AdministrationRequest, runId: string) {
    this.assertAdmin(request);
    const run = await this.store.findRun(runId);
    if (!run) throw new NotFoundException();
    return this.runResponse(run);
  }

  async exportRun(request: AdministrationRequest, runId: string) {
    this.assertAdmin(request);
    const run = await this.store.findRun(runId);
    if (!run) throw new NotFoundException();
    return {
      exportVersion: "benchmark-aggregate-v1",
      runId: run._id,
      dataset: {
        datasetId: run.datasetId,
        version: run.datasetVersion,
        sha256: run.datasetSha256,
      },
      promptVersion: run.promptVersion,
      rubricVersion: run.rubricVersion,
      rubricSha256: run.rubricSha256,
      candidates: run.candidates,
      startedAt: run.startedAt.toISOString(),
      completedAt: run.completedAt?.toISOString() ?? null,
      status: run.status,
      failureClassification: run.failureClassification,
      aggregates: run.aggregates ?? [],
    };
  }

  private readonly runResponse = (run: GovernedBenchmarkRun) => ({
    runId: run._id,
    datasetId: run.datasetId,
    datasetVersion: run.datasetVersion,
    datasetSha256: run.datasetSha256,
    promptVersion: run.promptVersion,
    rubricVersion: run.rubricVersion,
    rubricSha256: run.rubricSha256,
    status: run.status,
    failureClassification: run.failureClassification,
    candidates: run.candidates,
    aggregates: run.aggregates ?? [],
    startedAt: run.startedAt.toISOString(),
    completedAt: run.completedAt?.toISOString() ?? null,
  });

  private assertAdmin(request: AdministrationRequest): string {
    if (!request.principal?.roles.includes("ADMIN"))
      throw new ForbiddenException();
    return request.principal.accountId;
  }

  private parse<T>(schema: z.ZodType<T>, value: unknown): T {
    const parsed = schema.safeParse(value);
    if (!parsed.success)
      throw new BadRequestException("Request validation failed");
    return parsed.data;
  }

  private async assertPrivacyApproval(
    cases: readonly unknown[],
    approval: BenchmarkPrivacyApproval,
  ): Promise<void> {
    if (fingerprint(cases) !== approval.contentSha256)
      throw new ForbiddenException(
        "Dataset content does not match the curated privacy approval",
      );
    const registered = await this.store.findPrivacyApproval(
      approval.approvalId,
    );
    if (!registered || fingerprint(registered) !== fingerprint(approval))
      throw new ForbiddenException(
        "Dataset privacy approval is not present in the curated registry",
      );
  }

  private async requiredDataset(datasetId: string, datasetVersion: string) {
    const parsedId = this.parse(identifier, datasetId);
    const parsedVersion = this.parse(version, datasetVersion);
    const dataset = await this.store.findDataset(parsedId, parsedVersion);
    if (!dataset) throw new NotFoundException();
    return dataset;
  }

  private async command<T>(
    actorId: string,
    operation: string,
    key: string | undefined,
    intent: unknown,
    action: () => Promise<T>,
    recover?: () => Promise<T | null>,
  ): Promise<T> {
    const parsedKey = this.parse(idempotencyKey, key);
    const commandId = `${actorId}:${operation}:${parsedKey}`;
    const intentFingerprint = fingerprint(intent);
    const existing = await this.store.reserveCommand({
      _id: commandId,
      actorId,
      operation,
      key: parsedKey,
      fingerprint: intentFingerprint,
      state: "PENDING",
    });
    if (existing) {
      if (existing.fingerprint !== intentFingerprint)
        throw new ConflictException("IDEMPOTENCY_CONFLICT");
      if (existing.state === "COMPLETED") return existing.response as T;
      const recovered = recover ? await recover() : null;
      if (recovered !== null) {
        await this.store.completeCommand(commandId, recovered);
        return recovered;
      }
      if (!recover)
        throw new ConflictException("IDEMPOTENCY_COMMAND_UNAVAILABLE");
    }
    try {
      const response = await action();
      await this.store.completeCommand(commandId, response);
      return response;
    } catch (error) {
      await this.store.failCommand(commandId);
      throw error;
    }
  }
}

@Controller("api/v1/admin/ai-benchmarks")
export class BenchmarkAdministrationController {
  constructor(private readonly service: BenchmarkAdministrationService) {}

  @Get("datasets")
  list(@Req() request: AdministrationRequest) {
    return this.service.list(request);
  }

  @Get("datasets/:datasetId/versions/:version")
  inspect(
    @Req() request: AdministrationRequest,
    @Param("datasetId") datasetId: string,
    @Param("version") datasetVersion: string,
  ) {
    return this.service.inspect(request, datasetId, datasetVersion);
  }

  @Post("datasets")
  create(
    @Req() request: AdministrationRequest,
    @Headers("idempotency-key") key: string | undefined,
    @Body() body: unknown,
  ) {
    return this.service.create(request, key, body);
  }

  @Post("datasets/:datasetId/versions/:version")
  createVersion(
    @Req() request: AdministrationRequest,
    @Headers("idempotency-key") key: string | undefined,
    @Param("datasetId") datasetId: string,
    @Param("version") sourceVersion: string,
    @Body() body: unknown,
  ) {
    return this.service.createVersion(
      request,
      key,
      datasetId,
      sourceVersion,
      body,
    );
  }

  @Post("datasets/:datasetId/versions/:version/archive")
  @HttpCode(200)
  archive(
    @Req() request: AdministrationRequest,
    @Headers("idempotency-key") key: string | undefined,
    @Param("datasetId") datasetId: string,
    @Param("version") datasetVersion: string,
  ) {
    return this.service.archive(request, key, datasetId, datasetVersion);
  }

  @Get("runs")
  listRuns(@Req() request: AdministrationRequest) {
    return this.service.listRuns(request);
  }

  @Post("runs")
  @HttpCode(202)
  run(
    @Req() request: AdministrationRequest,
    @Headers("idempotency-key") key: string | undefined,
    @Body() body: unknown,
  ) {
    return this.service.run(request, key, body);
  }

  @Get("runs/:runId")
  inspectRun(
    @Req() request: AdministrationRequest,
    @Param("runId") runId: string,
  ) {
    return this.service.inspectRun(request, runId);
  }

  @Get("runs/:runId/export")
  exportRun(
    @Req() request: AdministrationRequest,
    @Param("runId") runId: string,
  ) {
    return this.service.exportRun(request, runId);
  }
}

export interface BenchmarkAdministrationDependencies {
  readonly store?: BenchmarkAdministrationStore;
  readonly executor?: BenchmarkAdministrationExecutor;
}

export const registerBenchmarkAdministrationModule = (
  configuration: ServiceConfiguration,
  dependencies: BenchmarkAdministrationDependencies = {},
): DynamicModule => {
  const providers: Provider[] = [
    {
      provide: STORE,
      useValue:
        dependencies.store ??
        new MongoBenchmarkAdministrationStore(configuration),
    },
    {
      provide: EXECUTOR,
      useValue:
        dependencies.executor ??
        new ConfiguredBenchmarkAdministrationExecutor(configuration),
    },
    {
      provide: BenchmarkAdministrationService,
      useFactory: (
        store: BenchmarkAdministrationStore,
        executor: BenchmarkAdministrationExecutor,
      ) => new BenchmarkAdministrationService(store, executor),
      inject: [STORE, EXECUTOR],
    },
  ];
  return {
    module: BenchmarkAdministrationModule,
    controllers: [BenchmarkAdministrationController],
    providers,
  };
};

@Module({})
// eslint-disable-next-line @typescript-eslint/no-extraneous-class
export class BenchmarkAdministrationModule {}
