import assert from "node:assert/strict";
import test from "node:test";

import type { ServiceConfiguration } from "../configuration/configuration.js";
import {
  ConsultationEntitlementClient,
  VersionedModelRouter,
  type EntitlementDecision,
} from "./model-routing.js";

const configuration = {
  CONSULTATION_BASE_URL: "http://consultation.test",
  CONSULTATION_TIMEOUT_MS: 100,
  PROVIDER_MODE: "APPROVED_REAL",
  ROUTING_POLICY_VERSION: "exact-revision-routing-v1",
  PROVIDER_APPROVAL_VERSION: "benchmark-approval-v1",
  FREE_PLUS_ROUTE: {
    provider: "GEMINI",
    model: "baseline-model",
    inputCostMicroUsdPerMillionTokens: 100_000,
    outputCostMicroUsdPerMillionTokens: 400_000,
  },
  PREMIUM_ROUTE: {
    provider: "OPENAI",
    model: "stronger-model",
    inputCostMicroUsdPerMillionTokens: 200_000,
    outputCostMicroUsdPerMillionTokens: 800_000,
  },
} as ServiceConfiguration;

const entitlement = (
  packageCode: EntitlementDecision["packageCode"],
): EntitlementDecision => ({
  packageCode,
  source: packageCode === "FREE" ? "DEFAULT_FREE" : "DEMO",
  policyVersion: "service-entitlement-v1",
  version: packageCode === "FREE" ? 0 : 1,
});

void test("routes FREE and PLUS to one baseline and PREMIUM to the stronger approved route", () => {
  const router = new VersionedModelRouter(configuration);
  const free = router.route(entitlement("FREE"));
  const plus = router.route(entitlement("PLUS"));
  const premium = router.route(entitlement("PREMIUM"));

  assert.equal(free.provider, "GEMINI");
  assert.equal(free.model, "baseline-model");
  assert.equal(plus.provider, free.provider);
  assert.equal(plus.model, free.model);
  assert.equal(premium.provider, "OPENAI");
  assert.equal(premium.model, "stronger-model");
  assert.equal(premium.providerApprovalVersion, "benchmark-approval-v1");
});

void test("forwards only the bearer context and ignores client tier query claims", async () => {
  const originalFetch = globalThis.fetch;
  let requestedUrl = "";
  let authorization = "";
  globalThis.fetch = (input: string | URL | Request, init?: RequestInit) => {
    requestedUrl = input instanceof Request ? input.url : input.toString();
    authorization = new Headers(init?.headers).get("authorization") ?? "";
    return Promise.resolve(
      new Response(
        JSON.stringify({
          accountId: "11111111-1111-4111-8111-111111111111",
          packageCode: "PREMIUM",
          source: "DEMO",
          sourceReference: "mb369-demo",
          effectiveFrom: "2026-09-16T00:00:00.000Z",
          effectiveUntil: "2026-09-23T00:00:00.000Z",
          policyVersion: "service-entitlement-v1",
          version: 1,
          decidedAt: "2026-09-16T01:00:00.000Z",
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      ),
    );
  };
  try {
    const decision = await new ConsultationEntitlementClient(
      configuration,
    ).current("end-user-jwt", "correlation-1");
    assert.equal(decision.packageCode, "PREMIUM");
    assert.equal(
      requestedUrl,
      "http://consultation.test/internal/v1/entitlements/current",
    );
    assert.equal(authorization, "Bearer end-user-jwt");
  } finally {
    globalThis.fetch = originalFetch;
  }
});
