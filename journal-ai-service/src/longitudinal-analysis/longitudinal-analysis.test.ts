import {
  aiConsentAuthorizationsFixture,
  pausedRead,
  sealedJournalRow,
  withdrawnAuthorizations,
} from "../analysis/consent.fixture.js";
import assert from "node:assert/strict";
import test from "node:test";
import {
  BadRequestException,
  ConflictException,
  ForbiddenException,
  NotFoundException,
} from "@nestjs/common";

import type { ConsentClient, ConsentDecision } from "../analysis/analysis.js";
import type { ServiceConfiguration } from "../configuration/configuration.js";
import {
  ProviderFailure,
  type LongitudinalProvider,
  type ProviderAnalysis,
} from "../llm-providers/llm-providers.js";
import type {
  EntitlementClient,
  EntitlementDecision,
  LongitudinalAnalysisRoute,
  LongitudinalModelRouter,
} from "../model-routing/model-routing.js";
import {
  LongitudinalAnalysisService,
  LongitudinalAnalysisWorker,
  MongoLongitudinalAnalysisRepository,
  type LongitudinalAnalysisJob,
  type LongitudinalAnalysisRepository,
  type LongitudinalAnalysisResult,
  type LongitudinalSourceLoad,
  type LongitudinalSourceRevision,
  type LongitudinalTerminalReason,
} from "./longitudinal-analysis.js";

class MemoryRepository implements LongitudinalAnalysisRepository {
  readonly jobs: LongitudinalAnalysisJob[] = [];
  readonly results: LongitudinalAnalysisResult[] = [];
  selectionReads = 0;
  sourceReads = 0;
  selectedSources: LongitudinalSourceRevision[] = [];
  sourceLoad: LongitudinalSourceLoad = { state: "CURRENT", sources: [] };

  findByKey(ownerAccountId: string, keyHash: string) {
    return Promise.resolve(
      this.jobs.find(
        (job) =>
          job.ownerAccountId === ownerAccountId && job.keyHash === keyHash,
      ) ?? null,
    );
  }

  create(job: LongitudinalAnalysisJob) {
    this.jobs.push(job);
    return Promise.resolve(job);
  }

  findOwned(ownerAccountId: string, jobId: string) {
    return Promise.resolve(
      this.jobs.find(
        (job) => job.ownerAccountId === ownerAccountId && job._id === jobId,
      ) ?? null,
    );
  }

  findResult(jobId: string) {
    return Promise.resolve(
      this.results.find((result) => result.jobId === jobId) ?? null,
    );
  }

  findResultByAnalysisId(ownerAccountId: string, analysisId: string) {
    return Promise.resolve(
      this.results.find(
        (result) =>
          result.userId === ownerAccountId && result.analysisId === analysisId,
      ) ?? null,
    );
  }

  selectSources() {
    this.selectionReads += 1;
    return Promise.resolve(this.selectedSources);
  }

  realLoad?: LongitudinalAnalysisRepository["loadSources"];
  loadSources(
    ownerAccountId: string,
    revisions: readonly LongitudinalSourceRevision[],
    authorize: () => Promise<boolean>,
  ) {
    this.sourceReads += 1;
    if (this.realLoad)
      return this.realLoad(ownerAccountId, revisions, authorize);
    return Promise.resolve(this.sourceLoad);
  }

  claim(workerId: string, now: Date, leaseExpiresAt: Date, jobId?: string) {
    const job = this.jobs.find(
      (candidate) =>
        (!jobId || candidate._id === jobId) &&
        ((candidate.status === "QUEUED" && candidate.nextAttemptAt <= now) ||
          (candidate.status === "RUNNING" &&
            candidate.leaseExpiresAt !== null &&
            candidate.leaseExpiresAt <= now)),
    );
    if (!job) return Promise.resolve(null);
    job.status = "RUNNING";
    job.leaseOwner = workerId;
    job.leaseExpiresAt = leaseExpiresAt;
    job.updatedAt = now;
    return Promise.resolve(job);
  }

  beginAttempt(jobId: string, workerId: string, now: Date) {
    const job = this.ownedLease(jobId, workerId);
    if (job.attemptCount >= 2) return Promise.resolve(null);
    job.attemptCount += 1;
    job.updatedAt = now;
    return Promise.resolve(job);
  }

  assignRoute(
    jobId: string,
    workerId: string,
    route: LongitudinalAnalysisRoute,
    now: Date,
  ) {
    const job = this.ownedLease(jobId, workerId);
    if (job.route) return Promise.resolve(null);
    job.route = route;
    job.updatedAt = now;
    return Promise.resolve(job);
  }

  requeue(jobId: string, workerId: string, now: Date) {
    const job = this.ownedLease(jobId, workerId);
    job.status = "QUEUED";
    job.nextAttemptAt = now;
    job.leaseOwner = null;
    job.leaseExpiresAt = null;
    job.updatedAt = now;
    return Promise.resolve();
  }

  fail(
    jobId: string,
    workerId: string,
    reason: LongitudinalTerminalReason,
    now: Date,
  ) {
    const job = this.ownedLease(jobId, workerId);
    job.status = "FAILED";
    job.terminalReason = reason;
    job.completedAt = now;
    job.updatedAt = now;
    job.leaseOwner = null;
    job.leaseExpiresAt = null;
    return Promise.resolve();
  }

  succeed(
    job: LongitudinalAnalysisJob,
    workerId: string,
    result: LongitudinalAnalysisResult,
    now: Date,
  ) {
    this.ownedLease(job._id, workerId);
    this.results.push(result);
    job.status = "SUCCEEDED";
    job.resultId = result.analysisId;
    job.completedAt = now;
    job.updatedAt = now;
    job.leaseOwner = null;
    job.leaseExpiresAt = null;
    return Promise.resolve();
  }

  private ownedLease(jobId: string, workerId: string) {
    const job = this.jobs.find(
      (candidate) =>
        candidate._id === jobId && candidate.leaseOwner === workerId,
    );
    if (!job) throw new Error("Lease not owned");
    return job;
  }
}

class SequenceConsent implements ConsentClient {
  async snapshot() {
    return aiConsentAuthorizationsFixture(await this.check());
  }
  constructor(private readonly decisions: (ConsentDecision | Error)[]) {}

  check() {
    const decision = this.decisions.shift();
    if (!decision || decision instanceof Error)
      return Promise.reject(decision ?? new Error("Consent unavailable"));
    return Promise.resolve(decision);
  }
}

class FixedEntitlement implements EntitlementClient {
  current() {
    return Promise.resolve(freeEntitlement);
  }
}

class FixedRouter implements LongitudinalModelRouter {
  route(entitlement: EntitlementDecision): LongitudinalAnalysisRoute {
    return {
      workload: "LONGITUDINAL",
      servicePlan: entitlement.packageCode,
      entitlementSource: entitlement.source,
      entitlementPolicyVersion: entitlement.policyVersion,
      entitlementVersion: entitlement.version,
      routingPolicyVersion: "exact-revision-routing-v1",
      providerApprovalVersion: "local-deterministic-v1",
      provider: "DETERMINISTIC_FAKE",
      model: "deterministic-reflection-v1",
      promptVersion: "longitudinal-v1",
      inputCostMicroUsdPerMillionTokens: 0,
      outputCostMicroUsdPerMillionTokens: 0,
    };
  }
}

class SequenceProvider implements LongitudinalProvider {
  calls = 0;
  readonly payloads: Parameters<LongitudinalProvider["analyze"]>[] = [];
  constructor(private readonly outputs: unknown[]) {}

  analyze(
    ...payload: Parameters<LongitudinalProvider["analyze"]>
  ): Promise<ProviderAnalysis> {
    this.payloads.push(payload);
    this.calls += 1;
    const output = this.outputs.shift();
    if (output instanceof Error) return Promise.reject(output);
    return Promise.resolve({
      output,
      latencyMs: 2,
      usage: {
        inputTokens: 20,
        outputTokens: 10,
        estimatedCostMicroUsd: 3,
      },
    });
  }
}

const configuration = {
  NODE_ENV: "test",
  LOG_LEVEL: "silent",
  SERVICE_NAME: "journal-ai-service",
  ANALYSIS_ENABLED: true,
  ANALYSIS_POLL_INTERVAL_MS: 10_000,
  ANALYSIS_LEASE_MS: 35_000,
  JOURNAL_IDEMPOTENCY_HMAC_KEY: Buffer.alloc(32, 4),
} as ServiceConfiguration;
const owner = "11111111-1111-4111-8111-111111111111";
const otherOwner = "22222222-2222-4222-8222-222222222222";
const granted: ConsentDecision = { authorized: true, reason: "GRANTED" };
const revoked: ConsentDecision = { authorized: false, reason: "REVOKED" };
const freeEntitlement: EntitlementDecision = {
  packageCode: "FREE",
  source: "DEFAULT_FREE",
  policyVersion: "service-entitlement-v1",
  version: 0,
};
const normalized = {
  contextSignals: ["STUDY_PRESSURE"],
  emotionIndicators: ["ANXIETY"],
  recurringThemes: ["ACADEMIC_WORKLOAD"],
  changesComparedWithPreviousPeriod: [
    { signal: "STUDY_PRESSURE", direction: "MORE_FREQUENT" },
  ],
  preferences: ["SHORT_GUIDED_ACTIVITY"],
  barriers: ["LOW_ENERGY"],
  helpfulPatterns: ["GROUNDING"],
};

const periods = () => {
  const currentEnd = new Date(Date.now() - DAY_MS);
  const currentStart = new Date(currentEnd.getTime() - 7 * DAY_MS);
  const previousEnd = currentStart;
  const previousStart = new Date(previousEnd.getTime() - 7 * DAY_MS);
  return {
    previousPeriod: {
      startAt: previousStart.toISOString(),
      endAt: previousEnd.toISOString(),
    },
    currentPeriod: {
      startAt: currentStart.toISOString(),
      endAt: currentEnd.toISOString(),
    },
  };
};

const DAY_MS = 24 * 60 * 60 * 1_000;

const sourceId = (index: number) =>
  `33333333-3333-4333-8333-${String(index).padStart(12, "0")}`;

const sources = (previousCount: number, currentCount: number) => {
  const values: LongitudinalSourceRevision[] = [];
  for (let index = 0; index < previousCount + currentCount; index += 1) {
    values.push({
      journalId: sourceId(index + 1),
      journalRevision: 1,
      period: index < previousCount ? "PREVIOUS" : "CURRENT",
      occurredAt: new Date(Date.now() - (index + 2) * DAY_MS),
    });
  }
  return values;
};

const request = (
  key = "longitudinal-command-0001",
  accountId = owner,
  body: unknown = periods(),
) => ({
  id: "correlation-1",
  headers: {
    authorization: "Bearer user-token",
    "idempotency-key": key,
  },
  principal: { accountId, roles: ["USER"] },
  body,
});

const subject = (
  consent: ConsentClient = new SequenceConsent([
    granted,
    granted,
    granted,
    granted,
    granted,
  ]),
  provider = new SequenceProvider([normalized]),
) => {
  const repository = new MemoryRepository();
  const worker = new LongitudinalAnalysisWorker(
    repository,
    consent,
    new FixedEntitlement(),
    new FixedRouter(),
    provider,
    configuration,
  );
  const service = new LongitudinalAnalysisService(
    repository,
    consent,
    worker,
    configuration,
  );
  return { repository, worker, service, provider };
};

const waitForTerminal = async (repository: MemoryRepository) => {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    const status = repository.jobs[0]?.status;
    if (status === "SUCCEEDED" || status === "FAILED") return;
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
  throw new Error("Longitudinal analysis did not reach a terminal state");
};

void test("persists sufficient exact-source evidence and exposes a minimized Care projection", async () => {
  const value = subject();
  value.repository.selectedSources = sources(3, 3);
  value.repository.sourceLoad = {
    state: "CURRENT",
    sources: value.repository.selectedSources.map((source) => ({
      journalId: source.journalId,
      journalRevision: source.journalRevision,
      period: source.period,
      occurredAt: source.occurredAt.toISOString(),
      text: `synthetic journal ${source.journalId}`,
    })),
  };

  const accepted = await value.service.create(request());
  await waitForTerminal(value.repository);
  const completed = await value.service.get(request(), accepted.jobId);
  assert.equal(completed.status, "SUCCEEDED");
  assert.deepEqual(value.provider.payloads, [
    [
      value.repository.sourceLoad.sources,
      {
        previousPeriodJournalEntryCount: 3,
        currentPeriodJournalEntryCount: 3,
        sufficientForComparison: true,
      },
      new FixedRouter().route(freeEntitlement),
    ],
  ]);
  assert.ok(completed.result);
  assert.equal(completed.result.dataCoverage.sufficientForComparison, true);
  assert.equal(completed.result.sourceJournalRevisions.length, 6);
  assert.equal(
    "text" in (completed.result.sourceJournalRevisions[0] ?? {}),
    false,
  );
  const analysisId = value.repository.results[0]?.analysisId;
  assert.ok(analysisId);
  const care = await value.service.getForCare(
    request("longitudinal-care-read-01"),
    owner,
    analysisId,
    "REASSESSMENT_SUMMARY",
  );
  assert.equal(care.analysisId, analysisId);
  assert.equal(JSON.stringify(care).includes("synthetic journal"), false);
  await assert.rejects(
    () =>
      value.service.get(
        request("other-owner-read", otherOwner),
        accepted.jobId,
      ),
    NotFoundException,
  );
});

void test("forces sparse and imbalanced comparisons to INSUFFICIENT_DATA", async () => {
  for (const [previousCount, currentCount] of [
    [2, 2],
    [3, 7],
  ] as const) {
    const value = subject();
    value.repository.selectedSources = sources(previousCount, currentCount);
    value.repository.sourceLoad = { state: "CURRENT", sources: [] };
    await value.service.create(
      request(
        `insufficient-command-${String(previousCount)}-${String(currentCount)}`,
      ),
    );
    await waitForTerminal(value.repository);
    const result = value.repository.results[0];
    assert.ok(result);
    assert.equal(result.dataCoverage.sufficientForComparison, false);
    assert.deepEqual(result.result.changesComparedWithPreviousPeriod, [
      {
        signal: "AVAILABLE_JOURNAL_COVERAGE",
        direction: "INSUFFICIENT_DATA",
      },
    ]);
  }
});

void test("returns one idempotent job for an exact request retry", async () => {
  const value = subject(
    new SequenceConsent([granted, granted, granted, granted, granted]),
  );
  value.repository.selectedSources = sources(3, 3);
  const comparison = periods();
  const accepted = await value.service.create(
    request("longitudinal-command-0001", owner, comparison),
  );
  const duplicate = await value.service.create(
    request("longitudinal-command-0001", owner, comparison),
  );
  assert.equal(duplicate.jobId, accepted.jobId);
  assert.equal(value.repository.jobs.length, 1);
});

void test("fails closed when consent is revoked before the provider attempt", async () => {
  const provider = new SequenceProvider([normalized]);
  const value = subject(new SequenceConsent([granted, revoked]), provider);
  value.repository.selectedSources = sources(3, 3);
  await value.service.create(request("longitudinal-revoked-01"));
  await waitForTerminal(value.repository);
  assert.equal(value.repository.jobs[0]?.terminalReason, "CONSENT_REVOKED");
  assert.equal(provider.calls, 0);
});

void test("fails safely when an exact source was deleted or revised", async () => {
  for (const state of ["SOURCE_DELETED", "SOURCE_REVISION_CHANGED"] as const) {
    const value = subject();
    value.repository.selectedSources = sources(3, 3);
    value.repository.sourceLoad = { state };
    await value.service.create(request(`longitudinal-source-${state}`));
    await waitForTerminal(value.repository);
    assert.equal(value.repository.jobs[0]?.terminalReason, state);
    assert.equal(value.provider.calls, 0);
  }
});

void test("records a stable provider failure without exposing provider output", async () => {
  const provider = new SequenceProvider([
    new ProviderFailure("PERMANENT", "UNAVAILABLE"),
  ]);
  const value = subject(
    new SequenceConsent([granted, granted, granted]),
    provider,
  );
  value.repository.selectedSources = sources(3, 3);
  await value.service.create(request("longitudinal-provider-failure"));
  await waitForTerminal(value.repository);
  assert.equal(
    value.repository.jobs[0]?.terminalReason,
    "PROVIDER_UNAVAILABLE",
  );
  assert.equal(value.repository.results.length, 0);
  assert.equal(provider.calls, 1);
});

void test("rejects invalid period bounds before consent or persistence", async () => {
  const value = subject();
  const invalid = periods();
  invalid.currentPeriod.startAt = new Date(
    new Date(invalid.currentPeriod.endAt).getTime() - 6 * DAY_MS,
  ).toISOString();
  await assert.rejects(
    () =>
      value.service.create(request("longitudinal-invalid-01", owner, invalid)),
    BadRequestException,
  );
  assert.equal(value.repository.jobs.length, 0);
});

void test("Care access rechecks current consent and fails closed", async () => {
  const value = subject(
    new SequenceConsent([granted, granted, granted, revoked]),
  );
  value.repository.selectedSources = sources(3, 3);
  await value.service.create(request("longitudinal-care-consent"));
  await waitForTerminal(value.repository);
  const analysisId = value.repository.results[0]?.analysisId;
  assert.ok(analysisId);
  await assert.rejects(
    () =>
      value.service.getForCare(
        request("longitudinal-care-revoked"),
        owner,
        analysisId,
        "REASSESSMENT_SUMMARY",
      ),
    ForbiddenException,
  );
});

void test("rejects an idempotency key reused for a valid different comparison", async () => {
  const value = subject(
    new SequenceConsent([granted, granted, granted, granted, granted]),
  );
  value.repository.selectedSources = sources(3, 3);
  await value.service.create(request("longitudinal-conflict-01"));
  const changed = periods();
  const shiftedStart = new Date(changed.previousPeriod.startAt);
  const shiftedEnd = new Date(changed.previousPeriod.endAt);
  shiftedStart.setUTCDate(shiftedStart.getUTCDate() - 1);
  shiftedEnd.setUTCDate(shiftedEnd.getUTCDate() - 1);
  changed.previousPeriod = {
    startAt: shiftedStart.toISOString(),
    endAt: shiftedEnd.toISOString(),
  };
  await assert.rejects(
    () =>
      value.service.create(request("longitudinal-conflict-01", owner, changed)),
    ConflictException,
  );
});

void test("longitudinal denial prevents source selection and late denial prevents provider use", async () => {
  for (const reason of [
    "MISSING",
    "REVOKED",
    "POLICY_OUTDATED",
    "DEPENDENCY_REQUIRED",
  ] as const) {
    const denied = subject(
      new SequenceConsent([{ authorized: false, reason }]),
    );
    await assert.rejects(
      () => denied.service.create(request(`longitudinal-denied-${reason}`)),
      (error) =>
        error instanceof ConflictException &&
        (error.getResponse() as { code: string }).code ===
          "AI_CONTEXT_CONSENT_REQUIRED",
    );
    assert.equal(denied.repository.selectionReads, 0);
    assert.equal(denied.repository.sourceReads, 0);
    assert.equal(denied.provider.calls, 0);
    const late = subject(
      new SequenceConsent([granted, granted, { authorized: false, reason }]),
    );
    await late.service.create(request(`longitudinal-final-${reason}`));
    await waitForTerminal(late.repository);
    assert.equal(late.provider.calls, 0);
  }
});

void test("longitudinal retry rechecks L2 before loading private sources", async () => {
  const provider = new SequenceProvider([
    new ProviderFailure("RETRYABLE", "UNAVAILABLE"),
    normalized,
  ]);
  const value = subject(
    new SequenceConsent([granted, granted, granted, revoked]),
    provider,
  );
  await value.service.create(request("longitudinal-retry-revoked"));
  await waitForTerminal(value.repository);
  assert.equal(provider.calls, 1);
  assert.equal(value.repository.sourceReads, 1);
  assert.equal(value.repository.jobs[0]?.terminalReason, "CONSENT_REVOKED");
});

void test("longitudinal scheduled restart fails without bearer or private source reads", async () => {
  const value = subject();
  const service = new LongitudinalAnalysisService(
    value.repository,
    new SequenceConsent([granted]),
    { dispatch: () => undefined } as unknown as LongitudinalAnalysisWorker,
    configuration,
  );
  await service.create(request("longitudinal-restart-lost-token"));
  await value.worker.run();
  assert.equal(
    value.repository.jobs[0]?.terminalReason,
    "AUTHORIZATION_CONTEXT_LOST",
  );
  assert.equal(value.repository.sourceReads, 0);
  assert.equal(value.provider.calls, 0);
});

void test("Care projection rechecks L2 after loading the normalized result", async () => {
  const value = subject(
    new SequenceConsent([granted, granted, granted, granted, revoked]),
  );
  await value.service.create(request("longitudinal-projection-final-check"));
  await waitForTerminal(value.repository);
  const analysisId = value.repository.results[0]?.analysisId;
  assert.ok(analysisId);
  await assert.rejects(
    () =>
      value.service.getForCare(
        request(),
        owner,
        analysisId,
        "REASSESSMENT_SUMMARY",
      ),
    ForbiddenException,
  );
});

void test("longitudinal worker reauthorizes L2 after a paused source read and decrypts nothing after withdrawal", async () => {
  for (const reason of ["REVOKED", "DEPENDENCY_REQUIRED"] as const) {
    let allowed = true;
    const consent: ConsentClient = {
      check: () => Promise.reject(new Error("unused")),
      snapshot: () =>
        Promise.resolve(
          allowed
            ? aiConsentAuthorizationsFixture()
            : withdrawnAuthorizations(reason),
        ),
    };
    const key = Buffer.alloc(32, 7);
    const real = new MongoLongitudinalAnalysisRepository({
      ...configuration,
      MONGODB_URI: "mongodb://127.0.0.1:1",
      MONGODB_DATABASE: "synthetic_mb806",
      MONGODB_CONNECTION_TIMEOUT_MS: 1,
      JOURNAL_ENCRYPTION_KEY: key,
    });
    const selected = sources(1, 1);
    const rows = selected.map((source) =>
      sealedJournalRow(key, owner, source.journalId),
    );
    const read = pausedRead();
    const internals = real as unknown as {
      connect: () => Promise<void>;
      journals: { find: () => { toArray: () => Promise<unknown[]> } };
      decrypt: (...args: unknown[]) => string;
    };
    const decrypt = internals.decrypt.bind(real);
    let decrypts = 0;
    internals.connect = () => Promise.resolve();
    internals.journals = {
      find: () => ({
        toArray: async () => {
          await read.wait();
          return rows;
        },
      }),
    };
    internals.decrypt = (...args) => {
      decrypts += 1;
      return decrypt(...args);
    };
    const value = subject(consent);
    value.repository.selectedSources = selected;
    value.repository.realLoad = real.loadSources.bind(real);
    await value.service.create(request(`longitudinal-paused-${reason}`));
    await read.started;
    allowed = false;
    read.resume();
    await waitForTerminal(value.repository);
    assert.equal(decrypts, 0);
    assert.equal(value.provider.calls, 0);
    assert.equal(
      value.repository.jobs[0]?.terminalReason,
      reason === "REVOKED" ? "CONSENT_REVOKED" : "CONSENT_REQUIRED",
    );
  }
});
