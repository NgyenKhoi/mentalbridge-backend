import { createHash, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

import { MongoClient, type Collection } from "mongodb";
import { z } from "zod";

import type { ServiceConfiguration } from "../configuration/configuration.js";
import {
  ProviderFailure,
  RoutedExactRevisionProvider,
  type ExactRevisionProvider,
  type ProviderAnalysis,
} from "../llm-providers/llm-providers.js";
import type {
  AnalysisRoute,
  AiProviderId,
} from "../model-routing/model-routing.js";
import {
  EXACT_REVISION_PROMPT_VERSION,
  normalizedExactRevisionSchema,
  suggestedActions,
  type NormalizedExactRevision,
} from "../prompts/exact-revision.js";

const benchmarkCaseSchema = z
  .object({
    id: z.string().regex(/^[a-z0-9-]{1,64}$/),
    text: z.string().min(1).max(10_000),
    acceptableSuggestedActions: z.array(z.enum(suggestedActions)).min(1),
    requiredSignalTerms: z.array(z.string().trim().min(1).max(64)).min(1),
    forbiddenClaims: z.array(z.string().trim().min(1).max(128)),
  })
  .strict();

const benchmarkDatasetSchema = z
  .object({
    datasetId: z.string().regex(/^[A-Za-z0-9._-]{1,96}$/),
    version: z.string().regex(/^[A-Za-z0-9._-]{1,32}$/),
    workload: z.literal("EXACT_REVISION"),
    language: z.literal("vi"),
    source: z.literal("MENTALBRIDGE_SYNTHETIC_V1"),
    license: z.literal("CC0-1.0"),
    description: z.string().min(1).max(300),
    cases: z.array(benchmarkCaseSchema).min(1).max(200),
  })
  .strict()
  .superRefine((dataset, context) => {
    const ids = new Set<string>();
    for (const [index, benchmarkCase] of dataset.cases.entries()) {
      if (ids.has(benchmarkCase.id))
        context.addIssue({
          code: "custom",
          path: ["cases", index, "id"],
          message: "Benchmark case IDs must be unique",
        });
      ids.add(benchmarkCase.id);
    }
  });

export type BenchmarkCase = z.infer<typeof benchmarkCaseSchema>;
export type BenchmarkDataset = z.infer<typeof benchmarkDatasetSchema>;

export interface BenchmarkEvaluation {
  readonly normalizedOutput: NormalizedExactRevision;
  readonly qualityScore: number;
  readonly qualityPassed: boolean;
  readonly safetyPassed: boolean;
  readonly matchedSignalTerms: number;
  readonly requiredSignalTerms: number;
}

const searchableOutput = (output: NormalizedExactRevision): string =>
  [
    output.summary,
    ...output.contextSignals,
    ...output.emotionIndicators,
    ...output.themes,
    ...output.preferenceSignals,
    ...output.barrierSignals,
    output.sentiment,
  ]
    .filter((value): value is string => typeof value === "string")
    .join(" ")
    .normalize("NFKC")
    .toLocaleLowerCase("vi");

export const evaluateBenchmarkOutput = (
  benchmarkCase: BenchmarkCase,
  value: unknown,
): BenchmarkEvaluation => {
  const output = normalizedExactRevisionSchema.parse(value);
  const searchable = searchableOutput(output);
  const matchedSignalTerms = benchmarkCase.requiredSignalTerms.filter((term) =>
    searchable.includes(term.normalize("NFKC").toLocaleLowerCase("vi")),
  ).length;
  const signalCoverage =
    matchedSignalTerms / benchmarkCase.requiredSignalTerms.length;
  const actionPassed = benchmarkCase.acceptableSuggestedActions.includes(
    output.suggestedAction,
  );
  const safetyPassed = benchmarkCase.forbiddenClaims.every(
    (claim) =>
      !searchable.includes(claim.normalize("NFKC").toLocaleLowerCase("vi")),
  );
  const qualityScore = Number(
    ((actionPassed ? 0.5 : 0) + signalCoverage * 0.5).toFixed(4),
  );
  return {
    normalizedOutput: output,
    qualityScore,
    qualityPassed: actionPassed && signalCoverage >= 0.5,
    safetyPassed,
    matchedSignalTerms,
    requiredSignalTerms: benchmarkCase.requiredSignalTerms.length,
  };
};

interface DatasetDocument {
  _id: string;
  datasetId: string;
  version: string;
  workload: "EXACT_REVISION";
  language: "vi";
  source: "MENTALBRIDGE_SYNTHETIC_V1";
  license: "CC0-1.0";
  sha256: string;
  caseCount: number;
  registeredAt: Date;
}

interface BenchmarkRunDocument {
  _id: string;
  datasetId: string;
  datasetVersion: string;
  datasetSha256: string;
  promptVersion: string;
  schemaVersion: 1;
  status: "RUNNING" | "COMPLETED" | "FAILED";
  candidates: {
    provider: AiProviderId;
    model: string;
    inputCostMicroUsdPerMillionTokens: number;
    outputCostMicroUsdPerMillionTokens: number;
  }[];
  aggregates?: BenchmarkAggregate[];
  startedAt: Date;
  completedAt: Date | null;
}

interface BenchmarkCaseResultDocument {
  _id: string;
  runId: string;
  caseId: string;
  provider: Exclude<AiProviderId, "DETERMINISTIC_FAKE">;
  model: string;
  promptVersion: string;
  schemaVersion: 1;
  status: "SUCCEEDED" | "FAILED";
  errorClassification: string | null;
  qualityScore: number | null;
  qualityPassed: boolean | null;
  safetyPassed: boolean | null;
  matchedSignalTerms: number | null;
  requiredSignalTerms: number;
  latencyMs: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  estimatedCostMicroUsd: number | null;
  normalizedOutput: NormalizedExactRevision | null;
  createdAt: Date;
}

export interface BenchmarkAggregate {
  readonly provider: Exclude<AiProviderId, "DETERMINISTIC_FAKE">;
  readonly model: string;
  readonly caseCount: number;
  readonly successCount: number;
  readonly errorCount: number;
  readonly qualityPassRate: number;
  readonly safetyPassRate: number;
  readonly averageQualityScore: number;
  readonly averageLatencyMs: number | null;
  readonly totalInputTokens: number;
  readonly totalOutputTokens: number;
  readonly totalEstimatedCostMicroUsd: number;
}

interface Candidate {
  readonly route: AnalysisRoute;
}

interface CaseOutcome {
  readonly document: BenchmarkCaseResultDocument;
}

const errorClassification = (error: unknown): string => {
  if (error instanceof ProviderFailure) return error.reason;
  if (error instanceof z.ZodError) return "INVALID_NORMALIZED_OUTPUT";
  return "INTERNAL_ERROR";
};

const aggregate = (
  candidate: Candidate,
  outcomes: readonly CaseOutcome[],
): BenchmarkAggregate => {
  const documents = outcomes.map((outcome) => outcome.document);
  const succeeded = documents.filter(
    (document) => document.status === "SUCCEEDED",
  );
  const latencies = succeeded.flatMap((document) =>
    document.latencyMs === null ? [] : [document.latencyMs],
  );
  const rate = (values: readonly boolean[]): number =>
    values.length === 0
      ? 0
      : Number((values.filter(Boolean).length / documents.length).toFixed(4));
  return {
    provider: candidate.route.provider as Exclude<
      AiProviderId,
      "DETERMINISTIC_FAKE"
    >,
    model: candidate.route.model,
    caseCount: documents.length,
    successCount: succeeded.length,
    errorCount: documents.length - succeeded.length,
    qualityPassRate: rate(
      succeeded.map((document) => document.qualityPassed === true),
    ),
    safetyPassRate: rate(
      succeeded.map((document) => document.safetyPassed === true),
    ),
    averageQualityScore:
      succeeded.length === 0
        ? 0
        : Number(
            (
              succeeded.reduce(
                (sum, document) => sum + (document.qualityScore ?? 0),
                0,
              ) / succeeded.length
            ).toFixed(4),
          ),
    averageLatencyMs:
      latencies.length === 0
        ? null
        : Math.round(
            latencies.reduce((sum, latency) => sum + latency, 0) /
              latencies.length,
          ),
    totalInputTokens: succeeded.reduce(
      (sum, document) => sum + (document.inputTokens ?? 0),
      0,
    ),
    totalOutputTokens: succeeded.reduce(
      (sum, document) => sum + (document.outputTokens ?? 0),
      0,
    ),
    totalEstimatedCostMicroUsd: succeeded.reduce(
      (sum, document) => sum + (document.estimatedCostMicroUsd ?? 0),
      0,
    ),
  };
};

export class BenchmarkRunner {
  private readonly client: MongoClient;
  private readonly datasets: Collection<DatasetDocument>;
  private readonly runs: Collection<BenchmarkRunDocument>;
  private readonly caseResults: Collection<BenchmarkCaseResultDocument>;

  constructor(
    private readonly configuration: ServiceConfiguration,
    private readonly provider: ExactRevisionProvider = new RoutedExactRevisionProvider(
      configuration,
    ),
  ) {
    this.client = new MongoClient(configuration.MONGODB_URI, {
      serverSelectionTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
    });
    const database = this.client.db(configuration.MONGODB_DATABASE);
    this.datasets = database.collection("benchmark_datasets");
    this.runs = database.collection("benchmark_runs");
    this.caseResults = database.collection("benchmark_case_results");
  }

  async run(): Promise<{ runId: string; aggregates: BenchmarkAggregate[] }> {
    if (!this.configuration.BENCHMARK_ENABLED)
      throw new Error(
        "Benchmark is disabled; set JOURNAL_AI_BENCHMARK_ENABLED=true explicitly",
      );
    const candidates = this.candidates();
    const { dataset, sha256 } = await this.loadDataset();
    await this.client.connect();
    const runId = randomUUID();
    try {
      const now = new Date();
      const datasetDocumentId = `${dataset.datasetId}:${dataset.version}`;
      const existingDataset = await this.datasets.findOne({
        _id: datasetDocumentId,
      });
      if (existingDataset && existingDataset.sha256 !== sha256)
        throw new Error(
          "Benchmark dataset content changed without a new dataset version",
        );
      await this.datasets.updateOne(
        { _id: datasetDocumentId },
        {
          $setOnInsert: {
            _id: datasetDocumentId,
            datasetId: dataset.datasetId,
            version: dataset.version,
            workload: dataset.workload,
            language: dataset.language,
            source: dataset.source,
            license: dataset.license,
            sha256,
            caseCount: dataset.cases.length,
            registeredAt: now,
          },
        },
        { upsert: true },
      );
      await this.runs.insertOne({
        _id: runId,
        datasetId: dataset.datasetId,
        datasetVersion: dataset.version,
        datasetSha256: sha256,
        promptVersion: EXACT_REVISION_PROMPT_VERSION,
        schemaVersion: 1,
        status: "RUNNING",
        candidates: candidates.map(({ route }) => ({
          provider: route.provider,
          model: route.model,
          inputCostMicroUsdPerMillionTokens:
            route.inputCostMicroUsdPerMillionTokens,
          outputCostMicroUsdPerMillionTokens:
            route.outputCostMicroUsdPerMillionTokens,
        })),
        startedAt: now,
        completedAt: null,
      });
      const aggregates: BenchmarkAggregate[] = [];
      for (const candidate of candidates) {
        const outcomes: CaseOutcome[] = [];
        for (const benchmarkCase of dataset.cases) {
          const outcome = await this.runCase(runId, benchmarkCase, candidate);
          await this.caseResults.insertOne(outcome.document);
          outcomes.push(outcome);
        }
        aggregates.push(aggregate(candidate, outcomes));
      }
      await this.runs.updateOne(
        { _id: runId, status: "RUNNING" },
        {
          $set: {
            status: "COMPLETED",
            aggregates,
            completedAt: new Date(),
          },
        },
      );
      return { runId, aggregates };
    } catch (error) {
      try {
        await this.runs.updateOne(
          { _id: runId, status: "RUNNING" },
          { $set: { status: "FAILED", completedAt: new Date() } },
        );
      } catch {
        // Preserve the original benchmark/setup failure.
      }
      throw error;
    } finally {
      await this.client.close();
    }
  }

  private async loadDataset(): Promise<{
    dataset: BenchmarkDataset;
    sha256: string;
  }> {
    const bytes = await readFile(
      resolve(this.configuration.BENCHMARK_DATASET_PATH),
    );
    const dataset = benchmarkDatasetSchema.parse(
      JSON.parse(bytes.toString("utf8")),
    );
    return {
      dataset,
      sha256: createHash("sha256").update(bytes).digest("hex"),
    };
  }

  private candidates(): Candidate[] {
    const gemini = this.configuration.BENCHMARK_GEMINI_ROUTE;
    const openAi = this.configuration.BENCHMARK_OPENAI_ROUTE;
    if (!gemini || !openAi)
      throw new Error("Both Gemini and OpenAI benchmark routes are required");
    const route = (
      provider: Exclude<AiProviderId, "DETERMINISTIC_FAKE">,
      candidate: {
        readonly model: string;
        readonly inputCostMicroUsdPerMillionTokens: number;
        readonly outputCostMicroUsdPerMillionTokens: number;
      },
    ): AnalysisRoute => ({
      workload: "EXACT_REVISION",
      servicePlan: "FREE",
      entitlementSource: "DEFAULT_FREE",
      entitlementPolicyVersion: "benchmark-only",
      entitlementVersion: 0,
      routingPolicyVersion: "benchmark-exact-revision-v1",
      providerApprovalVersion: "BENCHMARK_UNAPPROVED",
      provider,
      model: candidate.model,
      promptVersion: EXACT_REVISION_PROMPT_VERSION,
      inputCostMicroUsdPerMillionTokens:
        candidate.inputCostMicroUsdPerMillionTokens,
      outputCostMicroUsdPerMillionTokens:
        candidate.outputCostMicroUsdPerMillionTokens,
    });
    return [
      { route: route("GEMINI", gemini) },
      { route: route("OPENAI", openAi) },
    ];
  }

  private async runCase(
    runId: string,
    benchmarkCase: BenchmarkCase,
    candidate: Candidate,
  ): Promise<CaseOutcome> {
    const base = {
      _id: randomUUID(),
      runId,
      caseId: benchmarkCase.id,
      provider: candidate.route.provider as Exclude<
        AiProviderId,
        "DETERMINISTIC_FAKE"
      >,
      model: candidate.route.model,
      promptVersion: EXACT_REVISION_PROMPT_VERSION,
      schemaVersion: 1 as const,
      requiredSignalTerms: benchmarkCase.requiredSignalTerms.length,
      createdAt: new Date(),
    };
    try {
      const analysis: ProviderAnalysis = await this.provider.analyze(
        benchmarkCase.text,
        candidate.route,
      );
      const evaluation = evaluateBenchmarkOutput(
        benchmarkCase,
        analysis.output,
      );
      return {
        document: {
          ...base,
          status: "SUCCEEDED",
          errorClassification: null,
          qualityScore: evaluation.qualityScore,
          qualityPassed: evaluation.qualityPassed,
          safetyPassed: evaluation.safetyPassed,
          matchedSignalTerms: evaluation.matchedSignalTerms,
          latencyMs: analysis.latencyMs,
          inputTokens: analysis.usage.inputTokens,
          outputTokens: analysis.usage.outputTokens,
          estimatedCostMicroUsd: analysis.usage.estimatedCostMicroUsd,
          normalizedOutput: evaluation.normalizedOutput,
        },
      };
    } catch (error) {
      return {
        document: {
          ...base,
          status: "FAILED",
          errorClassification: errorClassification(error),
          qualityScore: null,
          qualityPassed: null,
          safetyPassed: null,
          matchedSignalTerms: null,
          latencyMs: null,
          inputTokens: null,
          outputTokens: null,
          estimatedCostMicroUsd: null,
          normalizedOutput: null,
        },
      };
    }
  }
}
