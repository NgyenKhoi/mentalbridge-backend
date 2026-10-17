import assert from "node:assert/strict";
import test from "node:test";
import { ConflictException, NotFoundException } from "@nestjs/common";

import type { ServiceConfiguration } from "../configuration/configuration.js";
import type { ProviderAnalysis } from "../llm-providers/llm-providers.js";
import type { AnalysisRoute } from "../model-routing/model-routing.js";
import {
  AnalysisService,
  AnalysisWorker,
  CareConsentClient,
  ProviderFailure,
  type AnalysisJob,
  type AnalysisRepository,
  type AnalysisResult,
  type AnalysisTerminalReason,
  type ConsentClient,
  type ConsentDecision,
  type EntitlementClient,
  type EntitlementDecision,
  type ExactRevisionProvider,
  type JournalSource,
  type ModelRouter,
} from "./analysis.js";

class MemoryRepository implements AnalysisRepository {
  readonly jobs: AnalysisJob[] = [];
  readonly results: AnalysisResult[] = [];
  sourceValue: JournalSource = { state: "CURRENT", text: "private reflection" };

  findByKey(owner: string, keyHash: string) {
    return Promise.resolve(
      this.jobs.find(
        (job) => job.ownerAccountId === owner && job.keyHash === keyHash,
      ) ?? null,
    );
  }
  create(job: AnalysisJob) {
    this.jobs.push(job);
    return Promise.resolve(job);
  }
  findOwned(owner: string, jobId: string) {
    return Promise.resolve(
      this.jobs.find(
        (job) => job.ownerAccountId === owner && job._id === jobId,
      ) ?? null,
    );
  }
  findResult(jobId: string) {
    return Promise.resolve(
      this.results.find((result) => result.jobId === jobId) ?? null,
    );
  }
  source() {
    return Promise.resolve(this.sourceValue);
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
    route: AnalysisRoute,
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
    reason: AnalysisTerminalReason,
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
    job: AnalysisJob,
    workerId: string,
    result: AnalysisResult,
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
  deleteForJournal(owner: string, journalId: string) {
    const deleted = new Set(
      this.jobs
        .filter(
          (job) => job.ownerAccountId === owner && job.journalId === journalId,
        )
        .map((job) => job._id),
    );
    for (let index = this.results.length - 1; index >= 0; index -= 1) {
      if (deleted.has(this.results[index]?.jobId ?? ""))
        this.results.splice(index, 1);
    }
    for (let index = this.jobs.length - 1; index >= 0; index -= 1) {
      if (deleted.has(this.jobs[index]?._id ?? "")) this.jobs.splice(index, 1);
    }
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
  readonly calls: string[] = [];
  constructor(private readonly decisions: ConsentDecision[]) {}
  check(bearer: string) {
    this.calls.push(bearer);
    const decision = this.decisions.shift() ?? this.decisions.at(-1);
    if (!decision) throw new Error("Consent unavailable");
    return Promise.resolve(decision);
  }
}

class SequenceProvider implements ExactRevisionProvider {
  calls = 0;
  constructor(private readonly results: unknown[]) {}
  analyze(): Promise<ProviderAnalysis> {
    this.calls += 1;
    const result = this.results.shift();
    if (result instanceof Error) return Promise.reject(result);
    return Promise.resolve({
      output: result,
      latencyMs: 1,
      usage: {
        inputTokens: 10,
        outputTokens: 5,
        estimatedCostMicroUsd: 2,
      },
    });
  }
}

class SequenceEntitlement implements EntitlementClient {
  readonly calls: string[] = [];
  constructor(private readonly decisions: EntitlementDecision[]) {}
  current(bearer: string) {
    this.calls.push(bearer);
    const decision = this.decisions.shift() ?? this.decisions.at(-1);
    if (!decision) throw new Error("Entitlement unavailable");
    return Promise.resolve(decision);
  }
}

class TestRouter implements ModelRouter {
  route(entitlement: EntitlementDecision): AnalysisRoute {
    return {
      workload: "EXACT_REVISION",
      servicePlan: entitlement.packageCode,
      entitlementSource: entitlement.source,
      entitlementPolicyVersion: entitlement.policyVersion,
      entitlementVersion: entitlement.version,
      routingPolicyVersion: "exact-revision-routing-v1",
      providerApprovalVersion: "local-deterministic-v1",
      provider: "DETERMINISTIC_FAKE",
      model: "deterministic-reflection-v1",
      promptVersion: "exact-revision-v2",
      inputCostMicroUsdPerMillionTokens: 0,
      outputCostMicroUsdPerMillionTokens: 0,
    };
  }
}

const configuration = {
  NODE_ENV: "test",
  LOG_LEVEL: "silent",
  SERVICE_NAME: "journal-ai-service",
  ANALYSIS_ENABLED: true,
  ANALYSIS_POLL_INTERVAL_MS: 10_000,
  ANALYSIS_LEASE_MS: 35_000,
  JOURNAL_IDEMPOTENCY_HMAC_KEY: Buffer.alloc(32, 2),
} as ServiceConfiguration;
const owner = "11111111-1111-4111-8111-111111111111";
const otherOwner = "22222222-2222-4222-8222-222222222222";
const journalId = "33333333-3333-4333-8333-333333333333";
const granted: ConsentDecision = { authorized: true, reason: "GRANTED" };
const freeEntitlement: EntitlementDecision = {
  packageCode: "FREE",
  source: "DEFAULT_FREE",
  policyVersion: "service-entitlement-v1",
  version: 0,
};
const normalized = {
  summary: "Bounded reflection",
  contextSignals: [],
  emotionIndicators: [],
  themes: [],
  preferenceSignals: [],
  barrierSignals: [],
  suggestedAction: "NONE",
};
const request = (account = owner, key = "analysis-command-0001") => ({
  id: "correlation-1",
  headers: {
    authorization: "Bearer user-jwt",
    "idempotency-key": key,
  },
  principal: { accountId: account, roles: ["USER"] },
});

const subject = (
  consent = new SequenceConsent([granted, granted]),
  provider = new SequenceProvider([normalized]),
  entitlement = new SequenceEntitlement([freeEntitlement, freeEntitlement]),
  router: ModelRouter = new TestRouter(),
) => {
  const repository = new MemoryRepository();
  const worker = new AnalysisWorker(
    repository,
    consent,
    entitlement,
    router,
    provider,
    configuration,
  );
  const service = new AnalysisService(
    repository,
    consent,
    worker,
    configuration,
  );
  return { repository, consent, entitlement, provider, worker, service };
};

const waitForTerminal = async (repository: MemoryRepository) => {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    const status = repository.jobs[0]?.status;
    if (status === "SUCCEEDED" || status === "FAILED") return;
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
  throw new Error("Analysis did not reach a terminal state");
};

void test("Care consent adapter preserves policy provenance", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (input, init) => {
    assert.equal(
      typeof input === "string"
        ? input
        : input instanceof URL
          ? input.href
          : input.url,
      "http://care.test/api/v1/consents/ai-processing/authorization",
    );
    const headers = new Headers(init?.headers);
    assert.equal(headers.get("authorization"), "Bearer synthetic-token");
    assert.equal(headers.get("x-correlation-id"), "correlation-id");
    return Promise.resolve(
      Response.json({
        authorized: true,
        reason: "GRANTED",
        consentType: "AI_PROCESSING",
        policyVersion: "ai-processing-capstone-v1",
        decidedAt: "2026-09-17T02:00:00.000Z",
      }),
    );
  };
  try {
    const client = new CareConsentClient({
      ...configuration,
      CARE_BASE_URL: "http://care.test",
      CARE_TIMEOUT_MS: 500,
    });
    assert.deepEqual(await client.check("synthetic-token", "correlation-id"), {
      authorized: true,
      reason: "GRANTED",
      policyVersion: "ai-processing-capstone-v1",
      decidedAt: "2026-09-17T02:00:00.000Z",
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

void test("runs a consented exact revision and persists only normalized output", async () => {
  const value = subject();
  const accepted = await value.service.create(request(), journalId, "1");
  assert.equal(accepted.status, "RUNNING");
  await waitForTerminal(value.repository);
  const completed = await value.service.get(request(), accepted.jobId);
  assert.equal(completed.status, "SUCCEEDED");
  assert.equal(completed.attemptCount, 1);
  assert.equal(completed.result?.provider, "DETERMINISTIC_FAKE");
  assert.equal(value.consent.calls.length, 2);
  assert.deepEqual(
    Object.keys(value.repository.results[0] ?? {}).includes("rawResponse"),
    false,
  );
});

void test("retries one eligible provider failure and returns the same idempotent job", async () => {
  const provider = new SequenceProvider([
    new ProviderFailure("RETRYABLE", "UNAVAILABLE"),
    normalized,
  ]);
  const value = subject(
    new SequenceConsent([granted, granted, granted, granted]),
    provider,
  );
  const accepted = await value.service.create(request(), journalId, "1");
  const duplicate = await value.service.create(request(), journalId, "1");
  assert.equal(duplicate.jobId, accepted.jobId);
  await waitForTerminal(value.repository);
  assert.equal(value.repository.jobs[0]?.status, "SUCCEEDED");
  assert.equal(value.repository.jobs[0].attemptCount, 2);
  assert.equal(provider.calls, 2);
  await assert.rejects(
    () =>
      value.service.create(
        request(owner, "analysis-command-0001"),
        "44444444-4444-4444-8444-444444444444",
        "1",
      ),
    ConflictException,
  );
});

void test("revocation before provider attempt fails without calling the provider", async () => {
  const consent = new SequenceConsent([
    granted,
    { authorized: false, reason: "REVOKED" },
  ]);
  const provider = new SequenceProvider([normalized]);
  const value = subject(consent, provider);
  await value.service.create(request(), journalId, "1");
  await waitForTerminal(value.repository);
  assert.equal(value.repository.jobs[0]?.terminalReason, "CONSENT_REVOKED");
  assert.equal(provider.calls, 0);
});

void test("fails a reclaimed lease closed when bearer context was lost", async () => {
  const value = subject();
  const now = new Date(Date.now() - 60_000);
  value.repository.jobs.push({
    _id: "55555555-5555-4555-8555-555555555555",
    ownerAccountId: owner,
    journalId,
    journalRevision: 1,
    keyHash: "a".repeat(43),
    fingerprint: "b".repeat(43),
    status: "RUNNING",
    attemptCount: 1,
    nextAttemptAt: now,
    leaseOwner: "dead-worker",
    leaseExpiresAt: now,
    terminalReason: null,
    resultId: null,
    createdAt: now,
    updatedAt: now,
    completedAt: null,
    route: null,
  });
  await value.worker.run();
  assert.equal(
    value.repository.jobs[0]?.terminalReason,
    "AUTHORIZATION_CONTEXT_LOST",
  );
  assert.equal(value.provider.calls, 0);
});

void test("keeps jobs owner scoped and couples journal deletion", async () => {
  const value = subject();
  const accepted = await value.service.create(request(), journalId, "1");
  await waitForTerminal(value.repository);
  await assert.rejects(
    () => value.service.get(request(otherOwner), accepted.jobId),
    NotFoundException,
  );
  await value.repository.deleteForJournal(owner, journalId);
  await assert.rejects(
    () => value.service.get(request(), accepted.jobId),
    NotFoundException,
  );
  assert.equal(value.repository.results.length, 0);
});

void test("rejects missing consent and stale revisions before creating a job", async () => {
  const missingConsent = subject(
    new SequenceConsent([{ authorized: false, reason: "MISSING" }]),
  );
  await assert.rejects(
    () => missingConsent.service.create(request(), journalId, "1"),
    ConflictException,
  );
  assert.equal(missingConsent.repository.jobs.length, 0);

  const stale = subject();
  stale.repository.sourceValue = { state: "STALE" };
  await assert.rejects(
    () => stale.service.create(request(), journalId, "1"),
    ConflictException,
  );
  assert.equal(stale.repository.jobs.length, 0);
});

void test("fails closed when Care becomes unavailable before the provider attempt", async () => {
  const consent = new SequenceConsent([granted]);
  const provider = new SequenceProvider([normalized]);
  const value = subject(consent, provider);
  await value.service.create(request(), journalId, "1");
  await waitForTerminal(value.repository);
  assert.equal(value.repository.jobs[0]?.terminalReason, "CONSENT_UNAVAILABLE");
  assert.equal(provider.calls, 0);
});

void test("fails closed when Consultation entitlement is unavailable", async () => {
  const provider = new SequenceProvider([normalized]);
  const value = subject(
    new SequenceConsent([granted, granted]),
    provider,
    new SequenceEntitlement([]),
  );
  await value.service.create(request(), journalId, "1");
  await waitForTerminal(value.repository);
  assert.equal(
    value.repository.jobs[0]?.terminalReason,
    "ENTITLEMENT_UNAVAILABLE",
  );
  assert.equal(provider.calls, 0);
});

void test("does not change provider route when entitlement changes before retry", async () => {
  const premiumEntitlement: EntitlementDecision = {
    packageCode: "PREMIUM",
    source: "DEMO",
    policyVersion: "service-entitlement-v1",
    version: 1,
  };
  const provider = new SequenceProvider([
    new ProviderFailure("RETRYABLE", "UNAVAILABLE"),
    normalized,
  ]);
  const router: ModelRouter = {
    route: (entitlement) => ({
      ...new TestRouter().route(entitlement),
      provider: entitlement.packageCode === "PREMIUM" ? "OPENAI" : "GEMINI",
      model:
        entitlement.packageCode === "PREMIUM"
          ? "premium-model"
          : "baseline-model",
      providerApprovalVersion: "benchmark-approval-v1",
    }),
  };
  const value = subject(
    new SequenceConsent([granted, granted, granted]),
    provider,
    new SequenceEntitlement([freeEntitlement, premiumEntitlement]),
    router,
  );
  await value.service.create(
    request(owner, "analysis-command-route-change"),
    journalId,
    "1",
  );
  await waitForTerminal(value.repository);
  assert.equal(value.repository.jobs[0]?.terminalReason, "ENTITLEMENT_CHANGED");
  assert.equal(provider.calls, 1);
});

void test("returns stable stale and deleted terminal reasons without provider calls", async () => {
  for (const sourceState of ["STALE", "DELETED"] as const) {
    const value = subject();
    const now = new Date();
    const job: AnalysisJob = {
      _id: randomJobId(sourceState),
      ownerAccountId: owner,
      journalId,
      journalRevision: 1,
      keyHash: sourceState === "STALE" ? "c".repeat(43) : "d".repeat(43),
      fingerprint: "e".repeat(43),
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
    value.repository.jobs.push(job);
    value.repository.sourceValue = { state: sourceState };
    value.worker.dispatch(job._id, "user-jwt", "correlation-1");
    await waitForTerminal(value.repository);
    assert.equal(
      job.terminalReason,
      sourceState === "STALE" ? "REVISION_STALE" : "JOURNAL_DELETED",
    );
    assert.equal(value.provider.calls, 0);
  }
});

void test("does not retry invalid or permanent provider failures", async () => {
  const invalid = subject(
    new SequenceConsent([granted, granted]),
    new SequenceProvider([{ unexpected: true }]),
  );
  await invalid.service.create(request(), journalId, "1");
  await waitForTerminal(invalid.repository);
  assert.equal(
    invalid.repository.jobs[0]?.terminalReason,
    "INVALID_PROVIDER_RESULT",
  );
  assert.equal(invalid.provider.calls, 1);

  const permanent = subject(
    new SequenceConsent([granted, granted]),
    new SequenceProvider([new ProviderFailure("PERMANENT", "UNAVAILABLE")]),
  );
  await permanent.service.create(
    request(owner, "analysis-command-0002"),
    journalId,
    "1",
  );
  await waitForTerminal(permanent.repository);
  assert.equal(
    permanent.repository.jobs[0]?.terminalReason,
    "PROVIDER_UNAVAILABLE",
  );
  assert.equal(permanent.repository.jobs[0].attemptCount, 1);
});

void test("times out each attempt and performs only one allowed retry", async () => {
  const repository = new MemoryRepository();
  const consent = new SequenceConsent([granted, granted, granted]);
  const provider: ExactRevisionProvider = {
    analyze: () => new Promise<ProviderAnalysis>(() => undefined),
  };
  const worker = new AnalysisWorker(
    repository,
    consent,
    new SequenceEntitlement([freeEntitlement, freeEntitlement]),
    new TestRouter(),
    provider,
    configuration,
    5,
  );
  const service = new AnalysisService(
    repository,
    consent,
    worker,
    configuration,
  );
  await service.create(request(), journalId, "1");
  await waitForTerminal(repository);
  assert.equal(repository.jobs[0]?.terminalReason, "PROVIDER_TIMEOUT");
  assert.equal(repository.jobs[0].attemptCount, 2);
});

const randomJobId = (state: "STALE" | "DELETED") =>
  state === "STALE"
    ? "66666666-6666-4666-8666-666666666666"
    : "77777777-7777-4777-8777-777777777777";
