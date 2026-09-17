import {
  BadRequestException,
  ConflictException,
  Controller,
  Get,
  HttpCode,
  Inject,
  Injectable,
  Module,
  NotFoundException,
  Param,
  Post,
  Req,
  Res,
  ServiceUnavailableException,
  UnauthorizedException,
  type DynamicModule,
  type OnApplicationShutdown,
  type OnModuleInit,
  type Provider,
} from "@nestjs/common";
import {
  Binary,
  MongoClient,
  type Collection,
  type Filter,
  type WithId,
} from "mongodb";
import { createDecipheriv, createHmac, randomUUID } from "node:crypto";
import { z } from "zod";

import type { ServiceConfiguration as Configuration } from "../configuration/configuration.js";
import type { Entry, Revision } from "../journals/journal.js";
import {
  ProviderFailure,
  RoutedExactRevisionProvider,
  type ExactRevisionProvider,
} from "../llm-providers/llm-providers.js";
import {
  ConsultationEntitlementClient,
  VersionedModelRouter,
  sameProviderRoute,
  type AnalysisRoute,
  type EntitlementClient,
  type ModelRouter,
} from "../model-routing/model-routing.js";
import { createLogger } from "../observability/logger.js";
import {
  normalizedExactRevisionSchema,
  type NormalizedExactRevision,
} from "../prompts/exact-revision.js";
import type { AuthenticatedRequest } from "../security/authenticated-principal.js";

const ANALYSIS_REPOSITORY = "ANALYSIS_REPOSITORY";
const CONSENT_CLIENT = "ANALYSIS_CONSENT_CLIENT";
const ENTITLEMENT_CLIENT = "ANALYSIS_ENTITLEMENT_CLIENT";
const MODEL_ROUTER = "ANALYSIS_MODEL_ROUTER";
const ANALYSIS_PROVIDER = "ANALYSIS_PROVIDER";
const ANALYSIS_WORKER = "ANALYSIS_WORKER";

const uuid = z.uuid();
const revisionSchema = z.coerce.number().int().min(1).max(200);
const idempotencyKeySchema = z.string().min(16).max(128);
const consentSchema = z
  .object({
    authorized: z.boolean(),
    reason: z.enum(["GRANTED", "MISSING", "REVOKED", "POLICY_OUTDATED"]),
    consentType: z.literal("AI_PROCESSING"),
    policyVersion: z.string().max(64).nullable(),
    decidedAt: z.iso.datetime({ offset: true }).nullable(),
  })
  .strict();
const binaryBuffer = (value: Buffer | Binary): Buffer =>
  Buffer.isBuffer(value) ? value : Buffer.from(value.buffer);

const digest = (key: Buffer, value: string): string =>
  createHmac("sha256", key).update(value).digest("base64url");

export type AnalysisTerminalReason =
  | "CONSENT_REQUIRED"
  | "CONSENT_REVOKED"
  | "CONSENT_UNAVAILABLE"
  | "ENTITLEMENT_UNAVAILABLE"
  | "ENTITLEMENT_CHANGED"
  | "AUTHORIZATION_CONTEXT_LOST"
  | "REVISION_STALE"
  | "JOURNAL_DELETED"
  | "PROVIDER_TIMEOUT"
  | "PROVIDER_UNAVAILABLE"
  | "INVALID_PROVIDER_RESULT"
  | "INTERNAL_ERROR";
type InternalJobStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED";
type ProviderResult = NormalizedExactRevision;

export interface AnalysisJob {
  _id: string;
  ownerAccountId: string;
  journalId: string;
  journalRevision: number;
  keyHash: string;
  fingerprint: string;
  status: InternalJobStatus;
  attemptCount: number;
  nextAttemptAt: Date;
  leaseOwner: string | null;
  leaseExpiresAt: Date | null;
  terminalReason: AnalysisTerminalReason | null;
  resultId: string | null;
  createdAt: Date;
  updatedAt: Date;
  completedAt: Date | null;
  route: AnalysisRoute | null;
}

export interface AnalysisResult {
  _id: string;
  analysisId: string;
  jobId: string;
  entryId: string;
  userId: string;
  journalRevision: number;
  workload: "EXACT_REVISION";
  servicePlan: "FREE" | "PLUS" | "PREMIUM";
  entitlementSource: "DEFAULT_FREE" | "DEMO" | "PAID";
  entitlementPolicyVersion: string;
  entitlementVersion: number;
  routingPolicyVersion: string;
  providerApprovalVersion: string;
  provider: "DETERMINISTIC_FAKE" | "GEMINI" | "OPENAI";
  model: string;
  promptVersion: string;
  schemaVersion: 1;
  latencyMs: number;
  inputTokens: number | null;
  outputTokens: number | null;
  estimatedCostMicroUsd: number | null;
  result: ProviderResult;
  createdAt: Date;
}

export interface JournalSource {
  state: "CURRENT" | "STALE" | "DELETED" | "MISSING";
  text?: string;
}

export interface AnalysisRepository {
  findByKey(
    ownerAccountId: string,
    keyHash: string,
  ): Promise<AnalysisJob | null>;
  create(job: AnalysisJob): Promise<AnalysisJob>;
  findOwned(ownerAccountId: string, jobId: string): Promise<AnalysisJob | null>;
  findResult(jobId: string): Promise<AnalysisResult | null>;
  source(
    ownerAccountId: string,
    journalId: string,
    revision: number,
  ): Promise<JournalSource>;
  claim(
    workerId: string,
    now: Date,
    leaseExpiresAt: Date,
    jobId?: string,
  ): Promise<AnalysisJob | null>;
  beginAttempt(
    jobId: string,
    workerId: string,
    now: Date,
  ): Promise<AnalysisJob | null>;
  assignRoute(
    jobId: string,
    workerId: string,
    route: AnalysisRoute,
    now: Date,
  ): Promise<AnalysisJob | null>;
  requeue(jobId: string, workerId: string, now: Date): Promise<void>;
  fail(
    jobId: string,
    workerId: string,
    reason: AnalysisTerminalReason,
    now: Date,
  ): Promise<void>;
  succeed(
    job: AnalysisJob,
    workerId: string,
    result: AnalysisResult,
    now: Date,
  ): Promise<void>;
  deleteForJournal(ownerAccountId: string, journalId: string): Promise<void>;
}

export interface ConsentDecision {
  authorized: boolean;
  reason: "GRANTED" | "MISSING" | "REVOKED" | "POLICY_OUTDATED";
  policyVersion?: string | null;
  decidedAt?: string | null;
}

export interface ConsentClient {
  check(bearerToken: string, correlationId: string): Promise<ConsentDecision>;
}

export interface AnalysisDependencies {
  repository?: AnalysisRepository;
  consentClient?: ConsentClient;
  entitlementClient?: EntitlementClient;
  router?: ModelRouter;
  provider?: ExactRevisionProvider;
}

interface AnalysisRequest extends AuthenticatedRequest {
  readonly id?: string;
}

class ConsentUnavailableError extends Error {}
export { ProviderFailure } from "../llm-providers/llm-providers.js";
export type { ExactRevisionProvider } from "../llm-providers/llm-providers.js";
export type {
  AnalysisRoute,
  EntitlementClient,
  EntitlementDecision,
  ModelRouter,
} from "../model-routing/model-routing.js";

const requestOwner = (request: AnalysisRequest): string => {
  const value = request.principal?.accountId;
  if (!value || !uuid.safeParse(value).success)
    throw new UnauthorizedException();
  return value;
};

const requestHeader = (
  request: AnalysisRequest,
  name: string,
): string | undefined => {
  const value = request.headers[name.toLowerCase()];
  return Array.isArray(value) ? value[0] : value;
};

const bearerToken = (request: AnalysisRequest): string => {
  const authorization = requestHeader(request, "authorization");
  if (!authorization?.startsWith("Bearer ")) throw new UnauthorizedException();
  return authorization.slice("Bearer ".length);
};

@Injectable()
export class MongoAnalysisRepository
  implements AnalysisRepository, OnApplicationShutdown
{
  private readonly client: MongoClient;
  private readonly jobs: Collection<AnalysisJob>;
  private readonly results: Collection<AnalysisResult>;
  private readonly journals: Collection<Entry>;
  private readonly configuration: Configuration;

  constructor(configuration: Configuration) {
    this.client = new MongoClient(configuration.MONGODB_URI, {
      connectTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
      serverSelectionTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
    });
    const database = this.client.db(configuration.MONGODB_DATABASE);
    this.jobs = database.collection<AnalysisJob>("analysis_jobs");
    this.results = database.collection<AnalysisResult>(
      "journal_analysis_results",
    );
    this.journals = database.collection<Entry>("journal_entries");
    this.configuration = configuration;
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

  async create(job: AnalysisJob) {
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

  async source(
    ownerAccountId: string,
    journalId: string,
    revisionNumber: number,
  ) {
    await this.connect();
    const journal = await this.journals.findOne({
      _id: journalId,
      ownerAccountId,
    });
    if (!journal) return { state: "MISSING" as const };
    if (journal.deleted) return { state: "DELETED" as const };
    if (journal.currentRevision !== revisionNumber)
      return { state: "STALE" as const };
    const revision = journal.revisions.find(
      (candidate: Revision) => candidate.revision === revisionNumber,
    );
    if (!revision) return { state: "MISSING" as const };
    return {
      state: "CURRENT" as const,
      text: this.decrypt(ownerAccountId, journalId, revision),
    };
  }

  private decrypt(
    ownerAccountId: string,
    journalId: string,
    revision: Revision,
  ) {
    if (revision.content.keyId !== this.configuration.JOURNAL_ENCRYPTION_KEY_ID)
      throw new ServiceUnavailableException();
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
    const filter: Filter<AnalysisJob> = {
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
    route: AnalysisRoute,
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
    reason: AnalysisTerminalReason,
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
    job: AnalysisJob,
    workerId: string,
    result: AnalysisResult,
    now: Date,
  ) {
    await this.connect();
    try {
      await this.results.insertOne(result);
    } catch (error) {
      if ((error as { code?: number }).code !== 11_000) throw error;
    }
    await this.jobs.updateOne(
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
  }

  async deleteForJournal(ownerAccountId: string, journalId: string) {
    await this.connect();
    const jobs = await this.jobs
      .find({ ownerAccountId, journalId })
      .project<{ _id: string }>({ _id: 1 })
      .toArray();
    const jobIds = jobs.map((job: WithId<{ _id: string }>) => job._id);
    if (jobIds.length > 0)
      await this.results.deleteMany({ jobId: { $in: jobIds } });
    await this.jobs.deleteMany({ ownerAccountId, journalId });
  }
}

@Injectable()
export class CareConsentClient implements ConsentClient {
  constructor(private readonly configuration: Configuration) {}

  async check(bearer: string, correlationId: string) {
    let response: Response;
    try {
      response = await fetch(
        new URL(
          "/api/v1/consents/ai-processing/authorization",
          this.configuration.CARE_BASE_URL,
        ),
        {
          headers: {
            authorization: `Bearer ${bearer}`,
            "x-correlation-id": correlationId,
          },
          signal: AbortSignal.timeout(this.configuration.CARE_TIMEOUT_MS),
        },
      );
    } catch (error) {
      throw new ConsentUnavailableError("Care consent is unavailable", {
        cause: error,
      });
    }
    if (!response.ok)
      throw new ConsentUnavailableError("Care consent is unavailable");
    const parsed = consentSchema.safeParse(await response.json());
    if (!parsed.success)
      throw new ConsentUnavailableError("Care consent response is invalid");
    return {
      authorized: parsed.data.authorized,
      reason: parsed.data.reason,
      policyVersion: parsed.data.policyVersion,
      decidedAt: parsed.data.decidedAt,
    };
  }
}

@Injectable()
export class AnalysisWorker implements OnModuleInit, OnApplicationShutdown {
  private readonly workerId = randomUUID();
  private readonly authorization = new Map<
    string,
    { bearer: string; correlationId: string; expiresAt: number }
  >();
  private timer?: NodeJS.Timeout;
  private readonly logger;

  constructor(
    @Inject(ANALYSIS_REPOSITORY)
    private readonly repository: AnalysisRepository,
    @Inject(CONSENT_CLIENT) private readonly consent: ConsentClient,
    @Inject(ENTITLEMENT_CLIENT)
    private readonly entitlement: EntitlementClient,
    @Inject(MODEL_ROUTER) private readonly router: ModelRouter,
    @Inject(ANALYSIS_PROVIDER) private readonly provider: ExactRevisionProvider,
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
    const source = await this.repository.source(
      job.ownerAccountId,
      job.journalId,
      job.journalRevision,
    );
    if (source.state !== "CURRENT" || !source.text) {
      await this.finishFailure(
        job,
        source.state === "DELETED" ? "JOURNAL_DELETED" : "REVISION_STALE",
      );
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
    let candidateRoute: AnalysisRoute;
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
      if (!sameProviderRoute(job.route, candidateRoute)) {
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
      await this.finishFailure(job, "PROVIDER_UNAVAILABLE");
      return;
    }
    try {
      const analysis = await this.withTimeout(
        this.provider.analyze(source.text, route),
      );
      const parsed = normalizedExactRevisionSchema.safeParse(analysis.output);
      if (!parsed.success) {
        await this.finishFailure(activeJob, "INVALID_PROVIDER_RESULT");
        return;
      }
      const completedAt = new Date();
      const analysisId = randomUUID();
      await this.repository.succeed(
        activeJob,
        this.workerId,
        {
          _id: analysisId,
          analysisId,
          jobId: activeJob._id,
          entryId: activeJob.journalId,
          userId: activeJob.ownerAccountId,
          journalRevision: activeJob.journalRevision,
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
          result: parsed.data,
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
        event: "analysis_worker_iteration_failed",
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
    job: AnalysisJob,
    reason: AnalysisTerminalReason,
  ): Promise<void> {
    await this.repository.fail(job._id, this.workerId, reason, new Date());
    this.authorization.delete(job._id);
  }
}

@Injectable()
export class AnalysisService {
  constructor(
    @Inject(ANALYSIS_REPOSITORY)
    private readonly repository: AnalysisRepository,
    @Inject(CONSENT_CLIENT) private readonly consent: ConsentClient,
    @Inject(ANALYSIS_WORKER) private readonly worker: AnalysisWorker,
    private readonly configuration: Configuration,
  ) {}

  async create(
    request: AnalysisRequest,
    journalIdValue: string,
    revisionValue: string,
  ) {
    if (!this.configuration.ANALYSIS_ENABLED)
      throw new ServiceUnavailableException();
    const journalId = uuid.safeParse(journalIdValue);
    const revision = revisionSchema.safeParse(revisionValue);
    const key = idempotencyKeySchema.safeParse(
      requestHeader(request, "idempotency-key"),
    );
    if (!journalId.success || !revision.success || !key.success)
      throw new BadRequestException();
    const ownerAccountId = requestOwner(request);
    const fingerprint = digest(
      this.configuration.JOURNAL_IDEMPOTENCY_HMAC_KEY,
      JSON.stringify({
        operation: "analyze-exact-revision",
        journalId: journalId.data,
        revision: revision.data,
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
        const bearer = bearerToken(request);
        await this.authorizeAndDispatch(
          existing._id,
          bearer,
          request.id ?? randomUUID(),
        );
      }
      return this.output(
        existing,
        await this.repository.findResult(existing._id),
      );
    }
    const source = await this.repository.source(
      ownerAccountId,
      journalId.data,
      revision.data,
    );
    if (source.state === "MISSING" || source.state === "DELETED")
      throw new NotFoundException();
    if (source.state !== "CURRENT") throw new ConflictException();
    const bearer = bearerToken(request);
    let decision: ConsentDecision;
    try {
      decision = await this.consent.check(bearer, request.id ?? randomUUID());
    } catch {
      throw new ServiceUnavailableException();
    }
    if (!decision.authorized) throw new ConflictException();
    const now = new Date();
    const job: AnalysisJob = {
      _id: randomUUID(),
      ownerAccountId,
      journalId: journalId.data,
      journalRevision: revision.data,
      keyHash,
      fingerprint,
      status: "QUEUED",
      attemptCount: 0,
      nextAttemptAt: now,
      leaseOwner: null,
      leaseExpiresAt: null,
      terminalReason: null,
      resultId: null,
      createdAt: now,
      updatedAt: now,
      completedAt: null,
      route: null,
    };
    try {
      await this.repository.create(job);
    } catch (error) {
      if ((error as { code?: number }).code !== 11_000) throw error;
      const raced = await this.repository.findByKey(ownerAccountId, keyHash);
      if (raced?.fingerprint !== fingerprint) throw new ConflictException();
      if (raced.status === "QUEUED" || raced.status === "RUNNING") {
        this.worker.dispatch(raced._id, bearer, request.id ?? randomUUID());
      }
      return this.output(raced, await this.repository.findResult(raced._id));
    }
    this.worker.dispatch(job._id, bearer, request.id ?? randomUUID());
    return this.output(job, null);
  }

  async get(request: AnalysisRequest, jobId: string) {
    if (!uuid.safeParse(jobId).success) throw new BadRequestException();
    const job = await this.repository.findOwned(requestOwner(request), jobId);
    if (!job) throw new NotFoundException();
    return this.output(job, await this.repository.findResult(job._id));
  }

  private async authorizeAndDispatch(
    jobId: string,
    bearer: string,
    correlationId: string,
  ): Promise<void> {
    let decision: ConsentDecision;
    try {
      decision = await this.consent.check(bearer, correlationId);
    } catch {
      throw new ServiceUnavailableException();
    }
    if (!decision.authorized) throw new ConflictException();
    this.worker.dispatch(jobId, bearer, correlationId);
  }

  private output(job: AnalysisJob, result: AnalysisResult | null) {
    return {
      jobId: job._id,
      journalId: job.journalId,
      journalRevision: job.journalRevision,
      status:
        job.status === "QUEUED" || job.status === "RUNNING"
          ? ("RUNNING" as const)
          : job.status,
      attemptCount: job.attemptCount,
      terminalReason: job.terminalReason,
      result: result
        ? {
            ...result.result,
            workload: result.workload,
            servicePlan: result.servicePlan,
            entitlementSource: result.entitlementSource,
            entitlementPolicyVersion: result.entitlementPolicyVersion,
            entitlementVersion: result.entitlementVersion,
            routingPolicyVersion: result.routingPolicyVersion,
            providerApprovalVersion: result.providerApprovalVersion,
            provider: result.provider,
            model: result.model,
            promptVersion: result.promptVersion,
            schemaVersion: result.schemaVersion,
            latencyMs: result.latencyMs,
            inputTokens: result.inputTokens,
            outputTokens: result.outputTokens,
            estimatedCostMicroUsd: result.estimatedCostMicroUsd,
            createdAt: result.createdAt.toISOString(),
          }
        : null,
      createdAt: job.createdAt.toISOString(),
      updatedAt: job.updatedAt.toISOString(),
      completedAt: job.completedAt?.toISOString() ?? null,
    };
  }
}

@Controller("api/v1")
export class AnalysisController {
  constructor(private readonly service: AnalysisService) {}

  @Post("journals/:journalId/revisions/:revision/analysis-jobs")
  @HttpCode(202)
  async create(
    @Req() request: AnalysisRequest,
    @Param("journalId") journalId: string,
    @Param("revision") revision: string,
    @Res({ passthrough: true })
    response: { setHeader(name: string, value: string): void },
  ) {
    const job = await this.service.create(request, journalId, revision);
    response.setHeader("Location", `/api/v1/analysis-jobs/${job.jobId}`);
    return job;
  }

  @Get("analysis-jobs/:jobId")
  get(@Req() request: AnalysisRequest, @Param("jobId") jobId: string) {
    return this.service.get(request, jobId);
  }
}

export const registerAnalysisModule = (
  configuration: Configuration,
  dependencies: AnalysisDependencies = {},
): DynamicModule => {
  const repositoryProvider: Provider = dependencies.repository
    ? { provide: ANALYSIS_REPOSITORY, useValue: dependencies.repository }
    : {
        provide: ANALYSIS_REPOSITORY,
        useFactory: () => new MongoAnalysisRepository(configuration),
      };
  return {
    module: AnalysisModule,
    controllers: [AnalysisController],
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
            useFactory: () => new VersionedModelRouter(configuration),
          },
      dependencies.provider
        ? { provide: ANALYSIS_PROVIDER, useValue: dependencies.provider }
        : {
            provide: ANALYSIS_PROVIDER,
            useFactory: () => new RoutedExactRevisionProvider(configuration),
          },
      {
        provide: ANALYSIS_WORKER,
        useFactory: (
          repository: AnalysisRepository,
          consent: ConsentClient,
          entitlement: EntitlementClient,
          router: ModelRouter,
          provider: ExactRevisionProvider,
        ) =>
          new AnalysisWorker(
            repository,
            consent,
            entitlement,
            router,
            provider,
            configuration,
          ),
        inject: [
          ANALYSIS_REPOSITORY,
          CONSENT_CLIENT,
          ENTITLEMENT_CLIENT,
          MODEL_ROUTER,
          ANALYSIS_PROVIDER,
        ],
      },
      {
        provide: AnalysisService,
        useFactory: (
          repository: AnalysisRepository,
          consent: ConsentClient,
          worker: AnalysisWorker,
        ) => new AnalysisService(repository, consent, worker, configuration),
        inject: [ANALYSIS_REPOSITORY, CONSENT_CLIENT, ANALYSIS_WORKER],
      },
    ],
  };
};

@Module({})
export class AnalysisModule {
  readonly moduleName = "analysis";
}
