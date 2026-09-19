import assert from "node:assert/strict";
import test from "node:test";

import type { ServiceConfiguration } from "../configuration/configuration.js";
import type { AnalysisRoute } from "../model-routing/model-routing.js";
import {
  ProviderFailure,
  RoutedExactRevisionProvider,
} from "./llm-providers.js";

const configuration = {
  GEMINI_BASE_URL: "https://gemini.test",
  GEMINI_API_KEY: "gemini-secret",
  OPENAI_BASE_URL: "https://openai.test",
  OPENAI_API_KEY: "openai-secret",
  PROVIDER_TIMEOUT_MS: 1_000,
} as ServiceConfiguration;

const route = (provider: "GEMINI" | "OPENAI"): AnalysisRoute => ({
  workload: "EXACT_REVISION",
  servicePlan: "FREE",
  entitlementSource: "DEFAULT_FREE",
  entitlementPolicyVersion: "service-entitlement-v1",
  entitlementVersion: 0,
  routingPolicyVersion: "exact-revision-routing-v1",
  providerApprovalVersion: "benchmark-approval-v1",
  provider,
  model: provider === "GEMINI" ? "gemini-model" : "openai-model",
  promptVersion: "exact-revision-v2",
  inputCostMicroUsdPerMillionTokens: 1_000_000,
  outputCostMicroUsdPerMillionTokens: 2_000_000,
});

const output = {
  summary: null,
  contextSignals: [],
  emotionIndicators: ["căng thẳng"],
  themes: [],
  preferenceSignals: [],
  barrierSignals: [],
  sentiment: null,
  modelConfidence: null,
  suggestedAction: "NONE",
};

void test("Gemini adapter requests structured JSON and captures usage without persisting raw response", async () => {
  const originalFetch = globalThis.fetch;
  let requestBody: Record<string, unknown> = {};
  globalThis.fetch = (_input: string | URL | Request, init?: RequestInit) => {
    const body = init?.body;
    if (typeof body !== "string") throw new Error("Expected JSON body");
    requestBody = JSON.parse(body) as Record<string, unknown>;
    return Promise.resolve(
      new Response(
        JSON.stringify({
          candidates: [
            { content: { parts: [{ text: JSON.stringify(output) }] } },
          ],
          usageMetadata: { promptTokenCount: 10, candidatesTokenCount: 5 },
        }),
        { status: 200 },
      ),
    );
  };
  try {
    const result = await new RoutedExactRevisionProvider(configuration).analyze(
      "synthetic journal",
      route("GEMINI"),
    );
    assert.equal(
      (requestBody.generationConfig as { responseMimeType?: unknown })
        .responseMimeType,
      "application/json",
    );
    assert.deepEqual(result.output, {
      contextSignals: [],
      emotionIndicators: ["căng thẳng"],
      themes: [],
      preferenceSignals: [],
      barrierSignals: [],
      suggestedAction: "NONE",
    });
    assert.equal(result.usage.estimatedCostMicroUsd, 20);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

void test("OpenAI adapter uses non-stored structured Responses output", async () => {
  const originalFetch = globalThis.fetch;
  let requestBody: Record<string, unknown> = {};
  globalThis.fetch = (_input: string | URL | Request, init?: RequestInit) => {
    const body = init?.body;
    if (typeof body !== "string") throw new Error("Expected JSON body");
    requestBody = JSON.parse(body) as Record<string, unknown>;
    return Promise.resolve(
      new Response(
        JSON.stringify({
          status: "completed",
          output: [
            {
              type: "message",
              content: [{ type: "output_text", text: JSON.stringify(output) }],
            },
          ],
          usage: { input_tokens: 8, output_tokens: 4 },
        }),
        { status: 200 },
      ),
    );
  };
  try {
    const result = await new RoutedExactRevisionProvider(configuration).analyze(
      "synthetic journal",
      route("OPENAI"),
    );
    assert.equal(requestBody.store, false);
    assert.equal(
      (requestBody.text as { format: { type: string } }).format.type,
      "json_schema",
    );
    assert.equal(result.usage.estimatedCostMicroUsd, 16);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

void test("does not fall back to another provider after retryable or malformed Gemini output", async () => {
  const originalFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = () => {
    calls += 1;
    return Promise.resolve(
      new Response(
        JSON.stringify({
          error: {
            code: 503,
            status: "UNAVAILABLE",
            details: [
              { retryDelay: "2s" },
              {
                violations: [
                  {
                    quotaId: "SyntheticQuota",
                    quotaMetric: "example.test/provider_requests",
                  },
                ],
              },
            ],
          },
        }),
        { status: 503 },
      ),
    );
  };
  try {
    await assert.rejects(
      () =>
        new RoutedExactRevisionProvider(configuration).analyze(
          "synthetic journal",
          route("GEMINI"),
        ),
      (error: unknown) =>
        error instanceof ProviderFailure &&
        error.kind === "RETRYABLE" &&
        error.diagnostics.httpStatus === 503 &&
        error.diagnostics.providerErrorCode === "UNAVAILABLE" &&
        error.diagnostics.retryAfterMs === 2_000 &&
        error.diagnostics.quotaIds?.[0] === "SyntheticQuota" &&
        error.diagnostics.quotaMetrics?.[0] ===
          "example.test/provider_requests",
    );
    assert.equal(calls, 1);

    globalThis.fetch = () =>
      Promise.resolve(
        new Response(
          JSON.stringify({
            candidates: [{ content: { parts: [{ text: "not-json" }] } }],
          }),
          { status: 200 },
        ),
      );
    await assert.rejects(
      () =>
        new RoutedExactRevisionProvider(configuration).analyze(
          "synthetic journal",
          route("GEMINI"),
        ),
      (error: unknown) =>
        error instanceof ProviderFailure &&
        error.kind === "PERMANENT" &&
        error.reason === "INVALID_RESULT",
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});
