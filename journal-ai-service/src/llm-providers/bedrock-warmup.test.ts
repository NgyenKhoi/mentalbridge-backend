import assert from "node:assert/strict";
import test from "node:test";

import { loadConfiguration } from "../configuration/configuration.js";
import {
  configuredBedrockModels,
  warmBedrockStructuredOutputs,
} from "./bedrock-warmup.js";

const baseEnvironment = {
  NODE_ENV: "development",
  IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
  IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
  IDENTITY_JWT_KEY_ID: "test-key",
  IDENTITY_JWT_PUBLIC_KEY: "test-public-key",
  JOURNAL_AI_PROVIDER_MODE: "DETERMINISTIC_FAKE",
  JOURNAL_AI_FREE_PLUS_PROVIDER: "BEDROCK",
  JOURNAL_AI_FREE_PLUS_MODEL: "profile.baseline-v1",
  JOURNAL_AI_FREE_PLUS_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "1",
  JOURNAL_AI_FREE_PLUS_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "2",
  JOURNAL_AI_PREMIUM_PROVIDER: "BEDROCK",
  JOURNAL_AI_PREMIUM_MODEL: "profile.premium-v1",
  JOURNAL_AI_PREMIUM_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "1",
  JOURNAL_AI_PREMIUM_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "2",
  JOURNAL_AI_BENCHMARK_BEDROCK_MODEL: "profile.baseline-v1",
  JOURNAL_AI_BENCHMARK_BEDROCK_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "1",
  JOURNAL_AI_BENCHMARK_BEDROCK_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS: "2",
  JOURNAL_AI_BEDROCK_SCHEMA_WARMUP_TIMEOUT_MS: "240000",
  AWS_BEARER_TOKEN_BEDROCK: "test-bedrock-token",
} satisfies NodeJS.ProcessEnv;

void test("warms all four schemas for each distinct configured Bedrock model", async () => {
  const configuration = loadConfiguration(baseEnvironment);
  assert.equal(configuration.BEDROCK_SCHEMA_WARMUP_TIMEOUT_MS, 240_000);
  const requests: {
    url: string;
    authorization: string | null;
    body: unknown;
  }[] = [];

  const result = await warmBedrockStructuredOutputs(
    configuration,
    (input, init) => {
      const body = init?.body;
      assert.equal(typeof body, "string");
      if (typeof body !== "string") throw new Error("Expected JSON body");
      requests.push({
        url:
          input instanceof URL
            ? input.href
            : typeof input === "string"
              ? input
              : input.url,
        authorization: new Headers(init?.headers).get("authorization"),
        body: JSON.parse(body),
      });
      return Promise.resolve(Response.json({ output: {} }));
    },
  );

  assert.deepEqual(configuredBedrockModels(configuration), [
    "profile.baseline-v1",
    "profile.premium-v1",
  ]);
  assert.deepEqual(result, {
    models: 2,
    schemasPerModel: 4,
    completedRequests: 8,
  });
  assert.equal(requests.length, 8);
  assert.ok(
    requests.every(
      (request) => request.authorization === "Bearer test-bedrock-token",
    ),
  );
  assert.deepEqual(
    new Set(
      requests.map(
        (request) =>
          (
            request.body as {
              outputConfig: {
                textFormat: { structure: { jsonSchema: { name: string } } };
              };
            }
          ).outputConfig.textFormat.structure.jsonSchema.name,
      ),
    ),
    new Set([
      "mentalbridge_exact_revision",
      "mentalbridge_longitudinal",
      "mentalbridge_support_guide_phrasing",
      "mentalbridge_companion_reply",
    ]),
  );
  assert.ok(requests.some((request) => request.url.includes("premium-v1")));
});

void test("fails without exposing a Bedrock response body", async () => {
  const configuration = loadConfiguration(baseEnvironment);

  await assert.rejects(
    () =>
      warmBedrockStructuredOutputs(configuration, () =>
        Promise.resolve(
          Response.json(
            { secretProviderPayload: "must-not-appear" },
            {
              status: 408,
              headers: { "x-amzn-errortype": "ModelTimeoutException:detail" },
            },
          ),
        ),
      ),
    (error: unknown) =>
      error instanceof Error &&
      error.message ===
        "Bedrock schema warm-up failed for EXACT_REVISION with HTTP 408 (ModelTimeoutException)" &&
      !error.message.includes("must-not-appear"),
  );
});
