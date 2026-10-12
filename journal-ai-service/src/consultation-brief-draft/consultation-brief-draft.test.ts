import assert from "node:assert/strict";
import test from "node:test";

import {
  BadRequestException,
  ForbiddenException,
  ServiceUnavailableException,
  UnauthorizedException,
} from "@nestjs/common";

import type { ConsentClient } from "../analysis/analysis.js";
import type { ConsultationBriefDraftProvider } from "../llm-providers/llm-providers.js";
import type {
  ConsultationBriefDraftModelRouter,
  EntitlementClient,
} from "../model-routing/model-routing.js";
import { consultationBriefDraftPrompt } from "../prompts/consultation-brief-draft.js";
import { ConsultationBriefDraftService } from "./consultation-brief-draft.js";

const request = {
  id: "10000000-0000-4000-8000-000000000001",
  headers: { authorization: "Bearer user-token" },
  principal: {
    accountId: "20000000-0000-4000-8000-000000000001",
    roles: ["USER"],
  },
  body: {
    appointmentId: "30000000-0000-4000-8000-000000000001",
    consultationBriefId: "40000000-0000-4000-8000-000000000001",
    consultationBriefVersion: 2,
    supportEvaluationId: "50000000-0000-4000-8000-000000000001",
    currentSituation:
      "Gáº§n Ä‘Ă¢y tĂ´i tháº¥y khĂ³ táº­p trung khi lĂ m viá»‡c.",
    userGoals: ["Trao Ä‘á»•i cĂ¡ch sáº¯p xáº¿p láº¡i nhá»‹p sinh hoáº¡t."],
    screeningContext: [
      {
        instrument: "PHQ9",
        domain: "DEPRESSIVE_SYMPTOMS",
        screeningLevel: "MILD",
        questionnaireVersion: "phq9-v2",
        scoringVersion: "phq9-score-v1",
        evaluatedAt: "2026-10-01T03:00:00Z",
        policyVersion: "support-evaluation-v2",
      },
      {
        instrument: "GAD7",
        domain: "ANXIETY_SYMPTOMS",
        screeningLevel: "MODERATE",
        questionnaireVersion: "gad7-v1",
        scoringVersion: "gad7-score-v1",
        evaluatedAt: "2026-10-01T03:00:00Z",
        policyVersion: "support-evaluation-v2",
      },
    ],
    sourceSetVersion: "consultation-brief-ai-source-v1",
  },
} as const;

const consent = (authorized = true): ConsentClient => ({
  check: () =>
    Promise.resolve({
      authorized,
      reason: authorized ? "GRANTED" : "REVOKED",
      policyVersion: "ai-processing-capstone-v2",
    }),
});

const entitlement: EntitlementClient = {
  current: () =>
    Promise.resolve({
      packageCode: "PLUS",
      source: "DEMO",
      policyVersion: "service-entitlement-v1",
      version: 4,
    }),
};

const router: ConsultationBriefDraftModelRouter = {
  route: (value) => ({
    workload: "CONSULTATION_BRIEF_DRAFT",
    servicePlan: value.packageCode,
    entitlementSource: value.source,
    entitlementPolicyVersion: value.policyVersion,
    entitlementVersion: value.version,
    routingPolicyVersion: "exact-revision-routing-v1",
    providerApprovalVersion: "benchmark-approval-v1",
    provider: "GEMINI",
    model: "gemini-approved",
    promptVersion: "consultation-brief-draft-v1",
    inputCostMicroUsdPerMillionTokens: 1,
    outputCostMicroUsdPerMillionTokens: 1,
  }),
};

const provider: ConsultationBriefDraftProvider = {
  draft: () =>
    Promise.resolve({
      output: {
        currentSituation:
          "TĂ´i muá»‘n trĂ¬nh bĂ y rĂµ hÆ¡n vá» kháº£ nÄƒng táº­p trung.",
        userGoals: [
          "CĂ¹ng chuyĂªn gia xem láº¡i nhá»‹p sinh hoáº¡t hiá»‡n táº¡i.",
        ],
      },
      latencyMs: 12,
      usage: {
        inputTokens: 20,
        outputTokens: 16,
        estimatedCostMicroUsd: 1,
      },
    }),
};

void test("returns an editable suggestion with consent and provider provenance", async () => {
  const result = await new ConsultationBriefDraftService(
    consent(),
    entitlement,
    router,
    provider,
  ).draft(request);

  assert.equal(result.sourceSetVersion, "consultation-brief-ai-source-v1");
  assert.equal(result.consentPolicyVersion, "ai-processing-capstone-v2");
  assert.equal(result.providerApprovalVersion, "benchmark-approval-v1");
  assert.equal(result.promptVersion, "consultation-brief-draft-v1");
  assert.equal(result.currentSituation.includes("táº­p trung"), true);
});

void test("fails before provider use when current AI consent is absent", async () => {
  let providerCalls = 0;
  const countingProvider: ConsultationBriefDraftProvider = {
    draft: () => {
      providerCalls += 1;
      return provider.draft(
        {
          currentSituation: request.body.currentSituation,
          userGoals: request.body.userGoals,
          screeningContext: request.body.screeningContext,
        },
        router.route({
          packageCode: "PLUS",
          source: "DEMO",
          policyVersion: "service-entitlement-v1",
          version: 4,
        }),
      );
    },
  };
  await assert.rejects(
    new ConsultationBriefDraftService(
      consent(false),
      entitlement,
      router,
      countingProvider,
    ).draft(request),
    ForbiddenException,
  );
  assert.equal(providerCalls, 0);
});

void test("rejects invalid sources and non-user actors", async () => {
  const service = new ConsultationBriefDraftService(
    consent(),
    entitlement,
    router,
    provider,
  );
  await assert.rejects(
    service.draft({ ...request, body: { ...request.body, userGoals: [] } }),
    BadRequestException,
  );
  await assert.rejects(
    service.draft({
      ...request,
      principal: { ...request.principal, roles: ["SPECIALIST"] },
    }),
    UnauthorizedException,
  );
});

void test("maps entitlement and malformed provider failures to unavailable", async () => {
  const unavailableEntitlement: EntitlementClient = {
    current: () => Promise.reject(new Error("unavailable")),
  };
  await assert.rejects(
    new ConsultationBriefDraftService(
      consent(),
      unavailableEntitlement,
      router,
      provider,
    ).draft(request),
    ServiceUnavailableException,
  );
  await assert.rejects(
    new ConsultationBriefDraftService(consent(), entitlement, router, {
      draft: () =>
        Promise.resolve({
          output: { currentSituation: "", userGoals: [] },
          latencyMs: 1,
          usage: {
            inputTokens: null,
            outputTokens: null,
            estimatedCostMicroUsd: null,
          },
        }),
    }).draft(request),
    ServiceUnavailableException,
  );
});

void test("prompt forbids diagnosis and preserves a strict editable shape", () => {
  const prompt = consultationBriefDraftPrompt({
    currentSituation: request.body.currentSituation,
    userGoals: request.body.userGoals,
    screeningContext: request.body.screeningContext,
  });
  assert.match(prompt.system, /do not invent.*diagnosis/i);
  assert.match(prompt.system, /Screening levels are context only/);
  assert.equal(prompt.schema.additionalProperties, false);
  assert.deepEqual(prompt.schema.required, ["currentSituation", "userGoals"]);
});
