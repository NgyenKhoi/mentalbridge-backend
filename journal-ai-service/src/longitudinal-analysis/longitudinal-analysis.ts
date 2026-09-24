import {
  BadRequestException,
  ConflictException,
  Controller,
  ForbiddenException,
  Get,
  HttpCode,
  Inject,
  Injectable,
  Module,
  NotFoundException,
  Param,
  Post,
  Query,
  Req,
  Res,
  ServiceUnavailableException,
  UnauthorizedException,
  type DynamicModule,
  type OnApplicationShutdown,
  type OnModuleInit,
  type Provider,
} from "@nestjs/common";
import { Binary, MongoClient, type Collection, type Filter } from "mongodb";
import { createDecipheriv, createHmac, randomUUID } from "node:crypto";
import { z } from "zod";

import {
  CareConsentClient,
  ProviderFailure,
  type ConsentClient,
  type ConsentDecision,
} from "../analysis/analysis.js";
import type { ServiceConfiguration as Configuration } from "../configuration/configuration.js";
import type { Entry, Revision } from "../journals/journal.js";
import {
  RoutedLongitudinalProvider,
  type LongitudinalProvider,
} from "../llm-providers/llm-providers.js";
import {
  ConsultationEntitlementClient,
  VersionedLongitudinalModelRouter,
  type EntitlementClient,
  type LongitudinalAnalysisRoute,
  type LongitudinalModelRouter,
} from "../model-routing/model-routing.js";
import { createLogger } from "../observability/logger.js";
import {
  normalizedLongitudinalSchema,
  type LongitudinalPromptCoverage,
  type LongitudinalPromptSource,
  type NormalizedLongitudinal,
} from "../prompts/longitudinal.js";
import type { AuthenticatedRequest } from "../security/authenticated-principal.js";

const REPOSITORY = "LONGITUDINAL_ANALYSIS_REPOSITORY";
const CONSENT_CLIENT = "LONGITUDINAL_ANALYSIS_CONSENT_CLIENT";
const ENTITLEMENT_CLIENT = "LONGITUDINAL_ANALYSIS_ENTITLEMENT_CLIENT";
const MODEL_ROUTER = "LONGITUDINAL_ANALYSIS_MODEL_ROUTER";
const PROVIDER = "LONGITUDINAL_ANALYSIS_PROVIDER";
const WORKER = "LONGITUDINAL_ANALYSIS_WORKER";

const DAY_MS = 24 * 60 * 60 * 1_000;
const MIN_PERIOD_MS = 7 * DAY_MS;
const MAX_PERIOD_MS = 31 * DAY_MS;
const MAX_SOURCES_PER_PERIOD = 50;
const MIN_SOURCES_PER_PERIOD = 3;
const MAX_COVERAGE_RATIO = 2;

const uuid = z.uuid();
const idempotencyKeySchema = z.string().min(16).max(128);
const periodInputSchema = z
  .object({
    startAt: z.iso.datetime({ offset: true }),
    endAt: z.iso.datetime({ offset: true }),
  })
  .strict();
const createRequestSchema = z
  .object({
    previousPeriod: periodInputSchema,
    currentPeriod: periodInputSchema,
    excludedJournalIds: z.array(uuid).max(100).default([]),
  })
  .strict();

const binaryBuffer = (value: Buffer | Binary): Buffer =>
  Buffer.isBuffer(value) ? value : Buffer.from(value.buffer);

const digest = (key: Buffer, value: string): string =>
  createHmac("sha256", key).update(value).digest("base64url");

export interface LongitudinalPeriod {
  readonly startAt: Date;
  readonly endAt: Date;
}

export interface LongitudinalSourceRevision {
  readonly journalId: string;
  readonly journalRevision: number;
  readonly period: "PREVIOUS" | "CURRENT";
  readonly occurredAt: Date;
}

export type LongitudinalTerminalReason =
  | "CONSENT_REQUIRED"
  | "CONSENT_REVOKED"
  | "CONSENT_UNAVAILABLE"
  | "ENTITLEMENT_UNAVAILABLE"
  | "ENTITLEMENT_CHANGED"
  | "AUTHORIZATION_CONTEXT_LOST"
  | "SOURCE_REVISION_CHANGED"
  | "SOURCE_DELETED"
  | "PROVIDER_TIMEOUT"
  | "PROVIDER_UNAVAILABLE"
  | "INVALID_PROVIDER_RESULT"
  | "INTERNAL_ERROR";

type InternalJobStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED";

export interface LongitudinalAnalysisJob {
  _id: string;
  ownerAccountId: string;
  keyHash: string;
  fingerprint: string;
  previousPeriod: LongitudinalPeriod;
  currentPeriod: LongitudinalPeriod;
  excludedJournalIds: string[];
  sourceJournalRevisions: LongitudinalSourceRevision[];
  dataCoverage: LongitudinalPromptCoverage;
  status: InternalJobStatus;
  attemptCount: number;
  nextAttemptAt: Date;
  leaseOwner: string | null;
  leaseExpiresAt: Date | null;
  terminalReason: LongitudinalTerminalReason | null;
  resultId: string | null;
  route: LongitudinalAnalysisRoute | null;
  createdAt: Date;
  updatedAt: Date;
  completedAt: Date | null;
}

export interface LongitudinalAnalysisResult {
  _id: string;
  analysisId: string;
  jobId: string;
  userId: string;
  previousPeriod: LongitudinalPeriod;
  currentPeriod: LongitudinalPeriod;
  sourceJournalRevisions: LongitudinalSourceRevision[];
  dataCoverage: LongitudinalPromptCoverage;
  workload: "LONGITUDINAL";
  servicePlan: "FREE" | "PLUS" | "PREMIUM";
  entitlementSource: "DEFAULT_FREE" | "DEMO" | "PAID";
  entitlementPolicyVersion: string;
  entitlementVersion: number;
  routingPolicyVersion: string;
  providerApprovalVersion: string;
  provider: "DETERMINISTIC_FAKE" | "GEMINI" | "OPENAI";
  model: string;
  promptVersion: "longitudinal-v1";
  schemaVersion: 1;
  latencyMs: number;
  inputTokens: number | null;
  outputTokens: number | null;
  estimatedCostMicroUsd: number | null;
  result: NormalizedLongitudinal;
  createdAt: Date;
}

export type LongitudinalSourceLoad =
  | { readonly state: "CURRENT"; readonly sources: LongitudinalPromptSource[] }
  | { readonly state: "SOURCE_REVISION_CHANGED" | "SOURCE_DELETED" };

export interface LongitudinalAnalysisRepository {
  findByKey(
    ownerAccountId: string,
    keyHash: string,
  ): Promise<LongitudinalAnalysisJob | null>;
  create(job: LongitudinalAnalysisJob): Promise<LongitudinalAnalysisJob>;
  findOwned(
    ownerAccountId: string,
    jobId: string,
  ): Promise<LongitudinalAnalysisJob | null>;
  findResult(jobId: string): Promise<LongitudinalAnalysisResult | null>;
  findResultByAnalysisId(
    ownerAccountId: string,
    analysisId: string,
  ): Promise<LongitudinalAnalysisResult | null>;
  selectSources(
    ownerAccountId: string,
    previousPeriod: LongitudinalPeriod,
    currentPeriod: LongitudinalPeriod,
    excludedJournalIds: readonly string[],
  ): Promise<LongitudinalSourceRevision[]>;
  loadSources(
    ownerAccountId: string,
    sources: readonly LongitudinalSourceRevision[],
  ): Promise<LongitudinalSourceLoad>;
  claim(
    workerId: string,
    now: Date,
    leaseExpiresAt: Date,
    jobId?: string,
  ): Promise<LongitudinalAnalysisJob | null>;
  beginAttempt(
    jobId: string,
    workerId: string,
    now: Date,
  ): Promise<LongitudinalAnalysisJob | null>;
  assignRoute(
    jobId: string,
    workerId: string,
    route: LongitudinalAnalysisRoute,
    now: Date,
  ): Promise<LongitudinalAnalysisJob | null>;
  requeue(jobId: string, workerId: string, now: Date): Promise<void>;
  fail(
    jobId: string,
    workerId: string,
    reason: LongitudinalTerminalReason,
    now: Date,
  ): Promise<void>;
  succeed(
    job: LongitudinalAnalysisJob,
    workerId: string,
    result: LongitudinalAnalysisResult,
    now: Date,
  ): Promise<void>;
}

export interface LongitudinalAnalysisDependencies {
  readonly repository?: LongitudinalAnalysisRepository;
  readonly consentClient?: ConsentClient;
  readonly entitlementClient?: EntitlementClient;
  readonly router?: LongitudinalModelRouter;
  readonly provider?: LongitudinalProvider;
}

interface LongitudinalRequest extends AuthenticatedRequest {
  readonly id?: string;
  readonly body?: unknown;
}

class SourceLimitExceededError extends Error {}

const requestOwner = (request: LongitudinalRequest): string => {
  const value = request.principal?.accountId;
  if (!value || !uuid.safeParse(value).success)
    throw new UnauthorizedException();
  return value;
};

const requestHeader = (
  request: LongitudinalRequest,
  name: string,
): string | undefined => {
  const value = request.headers[name.toLowerCase()];
  return Array.isArray(value) ? value[0] : value;
};

const bearerToken = (request: LongitudinalRequest): string => {
  const authorization = requestHeader(request, "authorization");
  if (!authorization?.startsWith("Bearer ")) throw new UnauthorizedException();
  return authorization.slice("Bearer ".length);
};

const publicPeriod = (period: LongitudinalPeriod) => ({
  startAt: period.startAt.toISOString(),
  endAt: period.endAt.toISOString(),
});

const publicSource = (source: LongitudinalSourceRevision) => ({
  journalId: source.journalId,
  journalRevision: source.journalRevision,
  period: source.period,
});

const sameRoute = (
  first: LongitudinalAnalysisRoute,
  second: LongitudinalAnalysisRoute,
): boolean =>
  first.routingPolicyVersion === second.routingPolicyVersion &&
  first.providerApprovalVersion === second.providerApprovalVersion &&
  first.provider === second.provider &&
  first.model === second.model;

@Injectable()
export class MongoLongitudinalAnalysisRepository
  implements LongitudinalAnalysisRepository, OnApplicationShutdown
{
  private readonly client: MongoClient;
  private readonly jobs: Collection<LongitudinalAnalysisJob>;
  private readonly results: Collection<LongitudinalAnalysisResult>;
  private readonly journals: Collection<Entry>;

  constructor(private readonly configuration: Configuration) {
    this.client = new MongoClient(configuration.MONGODB_URI, {
      connectTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
      serverSelectionTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
    });
    const database = this.client.db(configuration.MONGODB_DATABASE);
    this.jobs = database.collection<LongitudinalAnalysisJob>(
      "longitudinal_analysis_jobs",
    );
    this.results = database.collection<LongitudinalAnalysisResult>(
      "journal_longitudinal_analysis_results",
    );
    this.journals = database.collection<Entry>("journal_entries");
  }

  private async connect(): Promise<void> {
    try {
      await this.client.connect();
    } catch (error) {
      throw new ServiceUnavailableException({ cause: error });
    }
  }

  async onApplicationShutdown(): Promise<void> {
    await this.client.close();
  }

  async findByKey(ownerAccountId: string, keyHash: string) {
    await this.connect();
    return this.jobs.findOne({ ownerAccountId, keyHash });
  }

  async create(job: LongitudinalAnalysisJob) {
    await this.connect();
    await this.jobs.insertOne(job);
    return job;
  }

  async findOwned(ownerAccountId: string, jobId: string) {
    await this.connect();
    return this.jobs.findOne({ _id: jobId, ownerAccountId });
  }

  async findResult(jobId: string) {
    await this.connect();
    return this.results.findOne({ jobId });
  }

  async findResultByAnalysisId(ownerAccountId: string, analysisId: string) {
    await this.connect();
    return this.results.findOne({ analysisId, userId: ownerAccountId });
  }

  async selectSources(
    ownerAccountId: string,
    previousPeriod: LongitudinalPeriod,
    currentPeriod: LongitudinalPeriod,
    excludedJournalIds: readonly string[],
  ) {
    await this.connect();
    const select = async (
      period: LongitudinalPeriod,
      label: LongitudinalSourceRevision["period"],
    ): Promise<LongitudinalSourceRevision[]> => {
      const rows = await this.journals
        .find({
          ownerAccountId,
          deleted: false,
          occurredAt: { $gte: period.startAt, $lt: period.endAt },
          ...(excludedJournalIds.length === 0
            ? {}
            : { _id: { $nin: [...excludedJournalIds] } }),
        })
        .project<Pick<Entry, "_id" | "currentRevision" | "occurredAt">>({
          _id: 1,
          currentRevision: 1,
          occurredAt: 1,
        })
        .sort({ occurredAt: 1, _id: 1 })
        .limit(MAX_SOURCES_PER_PERIOD + 1)
        .toArray();
      if (rows.length > MAX_SOURCES_PER_PERIOD)
        throw new SourceLimitExceededError();
      return rows.map((row) => ({
        journalId: row._id,
        journalRevision: row.currentRevision,
        period: label,
        occurredAt: row.occurredAt,
      }));
    };
    const [previous, current] = await Promise.all([
      select(previousPeriod, "PREVIOUS"),
      select(currentPeriod, "CURRENT"),
    ]);
    return [...previous, ...current];
  }

  async loadSources(
    ownerAccountId: string,
    sources: readonly LongitudinalSourceRevision[],
  ): Promise<LongitudinalSourceLoad> {
    await this.connect();
    if (sources.length === 0) return { state: "CURRENT", sources: [] };
    const journals = await this.journals
      .find({
        ownerAccountId,
        _id: { $in: sources.map((source) => source.journalId) },
      })
      .toArray();
    const byId = new Map(journals.map((journal) => [journal._id, journal]));
    const promptSources: LongitudinalPromptSource[] = [];
    for (const source of sources) {
      const journal = byId.get(source.journalId);
      if (!journal || journal.deleted) return { state: "SOURCE_DELETED" };
      if (journal.currentRevision !== source.journalRevision)
        return { state: "SOURCE_REVISION_CHANGED" };
      const revision = journal.revisions.find(
        (candidate) => candidate.revision === source.journalRevision,
      );
      if (!revision) return { state: "SOURCE_REVISION_CHANGED" };
      promptSources.push({
        journalId: source.journalId,
        journalRevision: source.journalRevision,
        period: source.period,
        occurredAt: source.occurredAt.toISOString(),
        text: this.decrypt(ownerAccountId, source.journalId, revision),
      });
    }
    return { state: "CURRENT", sources: promptSources };
  }

  private decrypt(
    ownerAccountId: string,
    journalId: string,
    revision: Revision,
  ): string {
    try {
      const decipher = createDecipheriv(
        "aes-256-gcm",
        this.configuration.JOURNAL_ENCRYPTION_KEY,
        binaryBuffer(revision.content.iv),
      );
      decipher.setAAD(
        Buffer.from(
          `${ownerAccountId}:${journalId}:${String(revision.revision)}`,
        ),
      );
      decipher.setAuthTag(binaryBuffer(revision.content.tag));
      return Buffer.concat([
        decipher.update(binaryBuffer(revision.content.ciphertext)),
        decipher.final(),
      ]).toString("utf8");
    } catch (error) {
      throw new ServiceUnavailableException({ cause: error });
    }
  }

  async claim(
    workerId: string,
    now: Date,
    leaseExpiresAt: Date,
    jobId?: string,
  ) {
    await this.connect();
    const filter: Filter<LongitudinalAnalysisJob> = {
      ...(jobId ? { _id: jobId } : {}),
      $or: [
        { status: "QUEUED", nextAttemptAt: { $lte: now } },
        { status: "RUNNING", leaseExpiresAt: { $lte: now } },
      ],
    };
    return this.jobs.findOneAndUpdate(
      filter,
      {
        $set: {
          status: "RUNNING",
          leaseOwner: workerId,
          leaseExpiresAt,
          updatedAt: now,
        },
      },
      { returnDocument: "after", sort: { nextAttemptAt: 1, createdAt: 1 } },
    );
  }

  async beginAttempt(jobId: string, workerId: string, now: Date) {
    await this.connect();
    return this.jobs.findOneAndUpdate(
      {
        _id: jobId,
        status: "RUNNING",
        leaseOwner: workerId,
        attemptCount: { $lt: 2 },
      },
      { $inc: { attemptCount: 1 }, $set: { updatedAt: now } },
      { returnDocument: "after" },
    );
  }

  async assignRoute(
    jobId: string,
    workerId: string,
    route: LongitudinalAnalysisRoute,
    now: Date,
  ) {
    await this.connect();
    return this.jobs.findOneAndUpdate(
      {
        _id: jobId,
        status: "RUNNING",
        leaseOwner: workerId,
        $or: [{ route: null }, { route: { $exists: false } }],
      },
      { $set: { route, updatedAt: now } },
      { returnDocument: "after" },
    );
  }

  async requeue(jobId: string, workerId: string, now: Date) {
    await this.connect();
    await this.jobs.updateOne(
      { _id: jobId, status: "RUNNING", leaseOwner: workerId },
      {
        $set: {
          status: "QUEUED",
          nextAttemptAt: now,
          leaseOwner: null,
          leaseExpiresAt: null,
          updatedAt: now,
        },
      },
    );
  }

  async fail(
    jobId: string,
    workerId: string,
    reason: LongitudinalTerminalReason,
    now: Date,
  ) {
    await this.connect();
    await this.jobs.updateOne(
      { _id: jobId, status: "RUNNING", leaseOwner: workerId },
      {
        $set: {
          status: "FAILED",
          terminalReason: reason,
          leaseOwner: null,
          leaseExpiresAt: null,
          updatedAt: now,
          completedAt: now,
        },
      },
    );
  }

  async succeed(
    job: LongitudinalAnalysisJob,
    workerId: string,
    result: LongitudinalAnalysisResult,
    now: Date,
  ) {
    await this.connect();
    try {
      await this.results.insertOne(result);
    } catch (error) {
      if ((error as { code?: number }).code !== 11_000) throw error;
    }
    const completed = await this.jobs.updateOne(
      { _id: job._id, status: "RUNNING", leaseOwner: workerId },
      {
        $set: {
          status: "SUCCEEDED",
          resultId: result.analysisId,
          leaseOwner: null,
          leaseExpiresAt: null,
          updatedAt: now,
          completedAt: now,
        },
      },
    );
    if (completed.matchedCount === 0)
      await this.results.deleteOne({ jobId: job._id });
  }
}

@Injectable()
export class LongitudinalAnalysisWorker
  implements OnModuleInit, OnApplicationShutdown
{
  private readonly workerId = randomUUID();
  private readonly authorization = new Map<
    string,
    { bearer: string; correlationId: string; expiresAt: number }
  >();
  private timer?: NodeJS.Timeout;
  private readonly logger;

  constructor(
    @Inject(REPOSITORY)
    private readonly repository: LongitudinalAnalysisRepository,
    @Inject(CONSENT_CLIENT) private readonly consent: ConsentClient,
    @Inject(ENTITLEMENT_CLIENT)
    private readonly entitlement: EntitlementClient,
    @Inject(MODEL_ROUTER) private readonly router: LongitudinalModelRouter,
    @Inject(PROVIDER) private readonly provider: LongitudinalProvider,
    private readonly configuration: Configuration,
    private readonly attemptTimeoutMs = 30_000,
  ) {
    this.logger = createLogger(configuration);
  }

  onModuleInit(): void {
    if (!this.configuration.ANALYSIS_ENABLED) return;
    this.timer = setInterval(() => {
      void this.runSafely();
    }, this.configuration.ANALYSIS_POLL_INTERVAL_MS);
    this.timer.unref();
  }

  onApplicationShutdown(): void {
    if (this.timer) clearInterval(this.timer);
  }

  dispatch(jobId: string, bearer: string, correlationId: string): void {
    this.purgeExpiredAuthorization();
    this.authorization.set(jobId, {
      bearer,
      correlationId,
      expiresAt: Date.now() + this.configuration.ANALYSIS_LEASE_MS * 2,
    });
    queueMicrotask(() => {
      void this.runSafely(jobId);
    });
  }

  async run(jobId?: string): Promise<void> {
    this.purgeExpiredAuthorization();
    const now = new Date();
    const job = await this.repository.claim(
      this.workerId,
      now,
      new Date(now.getTime() + this.configuration.ANALYSIS_LEASE_MS),
      jobId,
    );
    if (!job) return;
    const recoveredResult = await this.repository.findResult(job._id);
    if (recoveredResult) {
      await this.repository.succeed(
        job,
        this.workerId,
        recoveredResult,
        new Date(),
      );
      this.authorization.delete(job._id);
      return;
    }
    const context = this.authorization.get(job._id);
    if (!context) {
      await this.finishFailure(job, "AUTHORIZATION_CONTEXT_LOST");
      return;
    }
    const source = await this.repository.loadSources(
      job.ownerAccountId,
      job.sourceJournalRevisions,
    );
    if (source.state !== "CURRENT") {
      await this.finishFailure(job, source.state);
      return;
    }
    let consent: ConsentDecision;
    try {
      consent = await this.consent.check(context.bearer, context.correlationId);
    } catch {
      await this.finishFailure(job, "CONSENT_UNAVAILABLE");
      return;
    }
    if (!consent.authorized) {
      await this.finishFailure(
        job,
        consent.reason === "REVOKED" ? "CONSENT_REVOKED" : "CONSENT_REQUIRED",
      );
      return;
    }
    let candidateRoute: LongitudinalAnalysisRoute;
    try {
      const entitlement = await this.entitlement.current(
        context.bearer,
        context.correlationId,
      );
      candidateRoute = this.router.route(entitlement);
    } catch {
      await this.finishFailure(job, "ENTITLEMENT_UNAVAILABLE");
      return;
    }
    let routedJob = job;
    if (job.route) {
      if (!sameRoute(job.route, candidateRoute)) {
        await this.finishFailure(job, "ENTITLEMENT_CHANGED");
        return;
      }
    } else {
      const assigned = await this.repository.assignRoute(
        job._id,
        this.workerId,
        candidateRoute,
        new Date(),
      );
      if (!assigned) {
        await this.finishFailure(job, "INTERNAL_ERROR");
        return;
      }
      routedJob = assigned;
    }
    const route = routedJob.route;
    if (!route) {
      await this.finishFailure(routedJob, "INTERNAL_ERROR");
      return;
    }
    const activeJob = await this.repository.beginAttempt(
      routedJob._id,
      this.workerId,
      new Date(),
    );
    if (!activeJob) {
      await this.finishFailure(routedJob, "PROVIDER_UNAVAILABLE");
      return;
    }
    try {
      const analysis = await this.withTimeout(
        this.provider.analyze(source.sources, job.dataCoverage, route),
      );
      const parsed = normalizedLongitudinalSchema.safeParse(analysis.output);
      if (!parsed.success) {
        await this.finishFailure(activeJob, "INVALID_PROVIDER_RESULT");
        return;
      }
      const normalized = this.enforceCoverage(parsed.data, job.dataCoverage);
      const completedAt = new Date();
      const analysisId = randomUUID();
      await this.repository.succeed(
        activeJob,
        this.workerId,
        {
          _id: analysisId,
          analysisId,
          jobId: activeJob._id,
          userId: activeJob.ownerAccountId,
          previousPeriod: activeJob.previousPeriod,
          currentPeriod: activeJob.currentPeriod,
          sourceJournalRevisions: activeJob.sourceJournalRevisions,
          dataCoverage: activeJob.dataCoverage,
          workload: route.workload,
          servicePlan: route.servicePlan,
          entitlementSource: route.entitlementSource,
          entitlementPolicyVersion: route.entitlementPolicyVersion,
          entitlementVersion: route.entitlementVersion,
          routingPolicyVersion: route.routingPolicyVersion,
          providerApprovalVersion: route.providerApprovalVersion,
          provider: route.provider,
          model: route.model,
          promptVersion: route.promptVersion,
          schemaVersion: 1,
          latencyMs: analysis.latencyMs,
          inputTokens: analysis.usage.inputTokens,
          outputTokens: analysis.usage.outputTokens,
          estimatedCostMicroUsd: analysis.usage.estimatedCostMicroUsd,
          result: normalized,
          createdAt: completedAt,
        },
        completedAt,
      );
      this.authorization.delete(job._id);
    } catch (error) {
      const failure =
        error instanceof ProviderFailure
          ? error
          : new ProviderFailure("RETRYABLE", "UNAVAILABLE");
      this.logger.warn({
        event: "longitudinal_provider_attempt_failed",
        jobId: activeJob._id,
        attemptCount: activeJob.attemptCount,
        provider: route.provider,
        model: route.model,
        failureKind: failure.kind,
        failureReason: failure.reason,
        ...failure.diagnostics,
      });
      if (failure.kind === "RETRYABLE" && activeJob.attemptCount < 2) {
        await this.repository.requeue(activeJob._id, this.workerId, new Date());
        queueMicrotask(() => {
          void this.runSafely(activeJob._id);
        });
        return;
      }
      await this.finishFailure(
        activeJob,
        failure.reason === "TIMEOUT"
          ? "PROVIDER_TIMEOUT"
          : failure.reason === "INVALID_RESULT"
            ? "INVALID_PROVIDER_RESULT"
            : "PROVIDER_UNAVAILABLE",
      );
    }
  }

  private enforceCoverage(
    result: NormalizedLongitudinal,
    coverage: LongitudinalPromptCoverage,
  ): NormalizedLongitudinal {
    if (coverage.sufficientForComparison) return result;
    return {
      ...result,
      changesComparedWithPreviousPeriod: [
        {
          signal: "AVAILABLE_JOURNAL_COVERAGE",
          direction: "INSUFFICIENT_DATA",
        },
      ],
    };
  }

  private async withTimeout<T>(result: Promise<T>): Promise<T> {
    let timer: NodeJS.Timeout | undefined;
    try {
      return await Promise.race([
        result,
        new Promise<never>((_resolve, reject) => {
          timer = setTimeout(() => {
            reject(new ProviderFailure("RETRYABLE", "TIMEOUT"));
          }, this.attemptTimeoutMs);
        }),
      ]);
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  private async runSafely(jobId?: string): Promise<void> {
    try {
      await this.run(jobId);
    } catch (error) {
      this.logger.error({
        event: "longitudinal_analysis_worker_iteration_failed",
        jobId,
        errorName: error instanceof Error ? error.name : "UnknownError",
      });
    }
  }

  private purgeExpiredAuthorization(): void {
    const now = Date.now();
    for (const [jobId, context] of this.authorization) {
      if (context.expiresAt <= now) this.authorization.delete(jobId);
    }
  }

  private async finishFailure(
    job: LongitudinalAnalysisJob,
    reason: LongitudinalTerminalReason,
  ): Promise<void> {
    await this.repository.fail(job._id, this.workerId, reason, new Date());
    this.authorization.delete(job._id);
  }
}

@Injectable()
export class LongitudinalAnalysisService {
  constructor(
    @Inject(REPOSITORY)
    private readonly repository: LongitudinalAnalysisRepository,
    @Inject(CONSENT_CLIENT) private readonly consent: ConsentClient,
    @Inject(WORKER) private readonly worker: LongitudinalAnalysisWorker,
    private readonly configuration: Configuration,
  ) {}

  async create(request: LongitudinalRequest) {
    if (!this.configuration.ANALYSIS_ENABLED)
      throw new ServiceUnavailableException();
    const parsed = createRequestSchema.safeParse(request.body);
    const key = idempotencyKeySchema.safeParse(
      requestHeader(request, "idempotency-key"),
    );
    if (!parsed.success || !key.success) throw new BadRequestException();
    const now = new Date();
    const previousPeriod = this.period(parsed.data.previousPeriod);
    const currentPeriod = this.period(parsed.data.currentPeriod);
    this.validatePeriods(previousPeriod, currentPeriod, now);
    const excludedJournalIds = [...new Set(parsed.data.excludedJournalIds)];
    if (excludedJournalIds.length !== parsed.data.excludedJournalIds.length)
      throw new BadRequestException();
    const ownerAccountId = requestOwner(request);
    const fingerprint = digest(
      this.configuration.JOURNAL_IDEMPOTENCY_HMAC_KEY,
      JSON.stringify({
        operation: "analyze-longitudinal-context",
        previousPeriod: publicPeriod(previousPeriod),
        currentPeriod: publicPeriod(currentPeriod),
        excludedJournalIds: [...excludedJournalIds].sort(),
      }),
    );
    const keyHash = digest(
      this.configuration.JOURNAL_IDEMPOTENCY_HMAC_KEY,
      key.data,
    );
    const existing = await this.repository.findByKey(ownerAccountId, keyHash);
    if (existing) {
      if (existing.fingerprint !== fingerprint) throw new ConflictException();
      if (existing.status === "QUEUED" || existing.status === "RUNNING") {
        await this.authorizeAndDispatch(
          existing._id,
          bearerToken(request),
          request.id ?? randomUUID(),
        );
      }
      return this.output(
        existing,
        await this.repository.findResult(existing._id),
      );
    }
    const bearer = bearerToken(request);
    await this.requireConsent(bearer, request.id ?? randomUUID(), false);
    let sourceJournalRevisions: LongitudinalSourceRevision[];
    try {
      sourceJournalRevisions = await this.repository.selectSources(
        ownerAccountId,
        previousPeriod,
        currentPeriod,
        excludedJournalIds,
      );
    } catch (error) {
      if (error instanceof SourceLimitExceededError)
        throw new BadRequestException();
      throw error;
    }
    const dataCoverage = this.coverage(sourceJournalRevisions);
    const job: LongitudinalAnalysisJob = {
      _id: randomUUID(),
      ownerAccountId,
      keyHash,
      fingerprint,
      previousPeriod,
      currentPeriod,
      excludedJournalIds,
      sourceJournalRevisions,
      dataCoverage,
      status: "QUEUED",
      attemptCount: 0,
      nextAttemptAt: now,
      leaseOwner: null,
      leaseExpiresAt: null,
      terminalReason: null,
      resultId: null,
      route: null,
      createdAt: now,
      updatedAt: now,
      completedAt: null,
    };
    try {
      await this.repository.create(job);
    } catch (error) {
      if ((error as { code?: number }).code !== 11_000) throw error;
      const raced = await this.repository.findByKey(ownerAccountId, keyHash);
      if (raced?.fingerprint !== fingerprint) throw new ConflictException();
      if (raced.status === "QUEUED" || raced.status === "RUNNING")
        this.worker.dispatch(raced._id, bearer, request.id ?? randomUUID());
      return this.output(raced, await this.repository.findResult(raced._id));
    }
    this.worker.dispatch(job._id, bearer, request.id ?? randomUUID());
    return this.output(job, null);
  }

  async get(request: LongitudinalRequest, jobId: string) {
    if (!uuid.safeParse(jobId).success) throw new BadRequestException();
    const job = await this.repository.findOwned(requestOwner(request), jobId);
    if (!job) throw new NotFoundException();
    return this.output(job, await this.repository.findResult(job._id));
  }

  async getForCare(
    request: LongitudinalRequest,
    userId: string,
    analysisId: string,
    purpose: string | undefined,
  ) {
    if (
      !uuid.safeParse(userId).success ||
      !uuid.safeParse(analysisId).success ||
      purpose !== "REASSESSMENT_SUMMARY"
    )
      throw new BadRequestException();
    if (requestOwner(request) !== userId) throw new NotFoundException();
    await this.requireConsent(
      bearerToken(request),
      request.id ?? randomUUID(),
      true,
    );
    const result = await this.repository.findResultByAnalysisId(
      userId,
      analysisId,
    );
    if (!result) throw new NotFoundException();
    return this.evidence(result);
  }

  private period(value: {
    startAt: string;
    endAt: string;
  }): LongitudinalPeriod {
    return { startAt: new Date(value.startAt), endAt: new Date(value.endAt) };
  }

  private validatePeriods(
    previous: LongitudinalPeriod,
    current: LongitudinalPeriod,
    now: Date,
  ): void {
    const previousDuration =
      previous.endAt.getTime() - previous.startAt.getTime();
    const currentDuration = current.endAt.getTime() - current.startAt.getTime();
    if (
      previousDuration < MIN_PERIOD_MS ||
      previousDuration > MAX_PERIOD_MS ||
      currentDuration !== previousDuration ||
      previous.endAt > current.startAt ||
      current.endAt > now
    )
      throw new BadRequestException();
  }

  private coverage(
    sources: readonly LongitudinalSourceRevision[],
  ): LongitudinalPromptCoverage {
    const previousPeriodJournalEntryCount = sources.filter(
      (source) => source.period === "PREVIOUS",
    ).length;
    const currentPeriodJournalEntryCount =
      sources.length - previousPeriodJournalEntryCount;
    const smaller = Math.min(
      previousPeriodJournalEntryCount,
      currentPeriodJournalEntryCount,
    );
    const larger = Math.max(
      previousPeriodJournalEntryCount,
      currentPeriodJournalEntryCount,
    );
    return {
      previousPeriodJournalEntryCount,
      currentPeriodJournalEntryCount,
      sufficientForComparison:
        smaller >= MIN_SOURCES_PER_PERIOD &&
        larger <= smaller * MAX_COVERAGE_RATIO,
    };
  }

  private async authorizeAndDispatch(
    jobId: string,
    bearer: string,
    correlationId: string,
  ): Promise<void> {
    await this.requireConsent(bearer, correlationId, false);
    this.worker.dispatch(jobId, bearer, correlationId);
  }

  private async requireConsent(
    bearer: string,
    correlationId: string,
    read: boolean,
  ): Promise<void> {
    let decision: ConsentDecision;
    try {
      decision = await this.consent.check(bearer, correlationId);
    } catch {
      throw new ServiceUnavailableException();
    }
    if (!decision.authorized) {
      if (read) throw new ForbiddenException();
      throw new ConflictException();
    }
  }

  private evidence(result: LongitudinalAnalysisResult) {
    return {
      analysisId: result.analysisId,
      previousPeriod: publicPeriod(result.previousPeriod),
      currentPeriod: publicPeriod(result.currentPeriod),
      sourceJournalRevisions: result.sourceJournalRevisions.map(publicSource),
      ...result.result,
      dataCoverage: result.dataCoverage,
      provider: result.provider,
      model: result.model,
      promptVersion: result.promptVersion,
      schemaVersion: result.schemaVersion,
      createdAt: result.createdAt.toISOString(),
    };
  }

  private output(
    job: LongitudinalAnalysisJob,
    result: LongitudinalAnalysisResult | null,
  ) {
    return {
      jobId: job._id,
      previousPeriod: publicPeriod(job.previousPeriod),
      currentPeriod: publicPeriod(job.currentPeriod),
      sourceJournalRevisions: job.sourceJournalRevisions.map(publicSource),
      dataCoverage: job.dataCoverage,
      status:
        job.status === "QUEUED" || job.status === "RUNNING"
          ? ("RUNNING" as const)
          : job.status,
      attemptCount: job.attemptCount,
      terminalReason: job.terminalReason,
      result: result ? this.evidence(result) : null,
      createdAt: job.createdAt.toISOString(),
      updatedAt: job.updatedAt.toISOString(),
      completedAt: job.completedAt?.toISOString() ?? null,
    };
  }
}

@Controller("api/v1/longitudinal-analysis-jobs")
export class LongitudinalAnalysisController {
  constructor(private readonly service: LongitudinalAnalysisService) {}

  @Post()
  @HttpCode(202)
  async create(
    @Req() request: LongitudinalRequest,
    @Res({ passthrough: true })
    response: { setHeader(name: string, value: string): void },
  ) {
    const job = await this.service.create(request);
    response.setHeader(
      "Location",
      `/api/v1/longitudinal-analysis-jobs/${job.jobId}`,
    );
    return job;
  }

  @Get(":jobId")
  get(@Req() request: LongitudinalRequest, @Param("jobId") jobId: string) {
    return this.service.get(request, jobId);
  }
}

@Controller("internal/v1/users")
export class LongitudinalAnalysisConsumerController {
  constructor(private readonly service: LongitudinalAnalysisService) {}

  @Get(":userId/longitudinal-analyses/:analysisId")
  get(
    @Req() request: LongitudinalRequest,
    @Param("userId") userId: string,
    @Param("analysisId") analysisId: string,
    @Query("purpose") purpose: string | undefined,
  ) {
    return this.service.getForCare(request, userId, analysisId, purpose);
  }
}

export const registerLongitudinalAnalysisModule = (
  configuration: Configuration,
  dependencies: LongitudinalAnalysisDependencies = {},
): DynamicModule => {
  const repositoryProvider: Provider = dependencies.repository
    ? { provide: REPOSITORY, useValue: dependencies.repository }
    : {
        provide: REPOSITORY,
        useFactory: () =>
          new MongoLongitudinalAnalysisRepository(configuration),
      };
  return {
    module: LongitudinalAnalysisModule,
    controllers: [
      LongitudinalAnalysisController,
      LongitudinalAnalysisConsumerController,
    ],
    providers: [
      repositoryProvider,
      dependencies.consentClient
        ? { provide: CONSENT_CLIENT, useValue: dependencies.consentClient }
        : {
            provide: CONSENT_CLIENT,
            useFactory: () => new CareConsentClient(configuration),
          },
      dependencies.entitlementClient
        ? {
            provide: ENTITLEMENT_CLIENT,
            useValue: dependencies.entitlementClient,
          }
        : {
            provide: ENTITLEMENT_CLIENT,
            useFactory: () => new ConsultationEntitlementClient(configuration),
          },
      dependencies.router
        ? { provide: MODEL_ROUTER, useValue: dependencies.router }
        : {
            provide: MODEL_ROUTER,
            useFactory: () =>
              new VersionedLongitudinalModelRouter(configuration),
          },
      dependencies.provider
        ? { provide: PROVIDER, useValue: dependencies.provider }
        : {
            provide: PROVIDER,
            useFactory: () => new RoutedLongitudinalProvider(configuration),
          },
      {
        provide: WORKER,
        useFactory: (
          repository: LongitudinalAnalysisRepository,
          consent: ConsentClient,
          entitlement: EntitlementClient,
          router: LongitudinalModelRouter,
          provider: LongitudinalProvider,
        ) =>
          new LongitudinalAnalysisWorker(
            repository,
            consent,
            entitlement,
            router,
            provider,
            configuration,
          ),
        inject: [
          REPOSITORY,
          CONSENT_CLIENT,
          ENTITLEMENT_CLIENT,
          MODEL_ROUTER,
          PROVIDER,
        ],
      },
      {
        provide: LongitudinalAnalysisService,
        useFactory: (
          repository: LongitudinalAnalysisRepository,
          consent: ConsentClient,
          worker: LongitudinalAnalysisWorker,
        ) =>
          new LongitudinalAnalysisService(
            repository,
            consent,
            worker,
            configuration,
          ),
        inject: [REPOSITORY, CONSENT_CLIENT, WORKER],
      },
    ],
  };
};

@Module({})
export class LongitudinalAnalysisModule {
  readonly moduleName = "longitudinal-analysis";
}
