import assert from "node:assert/strict";
import test from "node:test";

import {
  BadRequestException,
  ForbiddenException,
  ServiceUnavailableException,
  UnauthorizedException,
} from "@nestjs/common";

import type { ConsentClient } from "../analysis/analysis.js";
import type { ServiceConfiguration } from "../configuration/configuration.js";
import type { SupportGuidePhrasingProvider } from "../llm-providers/llm-providers.js";
import { supportGuidePhrasingPrompt } from "../prompts/support-guide-phrasing.js";
import { SupportGuidePhrasingService } from "./support-guide-phrasing.js";

const configuration = {
  PROVIDER_MODE: "APPROVED_REAL",
  FREE_PLUS_ROUTE: {
    provider: "GEMINI",
    model: "gemini-approved",
    inputCostMicroUsdPerMillionTokens: 1,
    outputCostMicroUsdPerMillionTokens: 1,
  },
} as ServiceConfiguration;

const request = {
  id: "10000000-0000-4000-8000-000000000001",
  headers: { authorization: "Bearer user-token" },
  principal: {
    accountId: "20000000-0000-4000-8000-000000000001",
    roles: ["USER"],
  },
  body: {
    approvedText: "Bản hướng dẫn đã được Care phê duyệt.",
    locale: "vi-VN",
  },
};

void test("returns only validated phrasing with provider provenance", async () => {
  const consent: ConsentClient = {
    check: () => Promise.resolve({ authorized: true, reason: "GRANTED" }),
  };
  const provider: SupportGuidePhrasingProvider = {
    phrase: () =>
      Promise.resolve({
        output: { text: "Một cách diễn đạt rõ ràng và gần gũi hơn." },
        latencyMs: 12,
        usage: {
          inputTokens: 10,
          outputTokens: 12,
          estimatedCostMicroUsd: 1,
        },
      }),
  };

  const result = await new SupportGuidePhrasingService(
    consent,
    provider,
    configuration,
  ).phrase(request);

  assert.deepEqual(result, {
    text: "Một cách diễn đạt rõ ràng và gần gũi hơn.",
    provider: "GEMINI",
    model: "gemini-approved",
    promptVersion: "support-guide-phrasing-v1",
    schemaVersion: 1,
  });
});

void test("rejects phrasing when current AI consent is absent", async () => {
  const consent: ConsentClient = {
    check: () => Promise.resolve({ authorized: false, reason: "MISSING" }),
  };
  const provider: SupportGuidePhrasingProvider = {
    phrase: () => Promise.reject(new Error("provider must not be called")),
  };

  await assert.rejects(
    new SupportGuidePhrasingService(consent, provider, configuration).phrase(
      request,
    ),
    ForbiddenException,
  );
});

void test("maps malformed provider output to dependency unavailable", async () => {
  const consent: ConsentClient = {
    check: () => Promise.resolve({ authorized: true, reason: "GRANTED" }),
  };
  const provider: SupportGuidePhrasingProvider = {
    phrase: () =>
      Promise.resolve({
        output: { text: "" },
        latencyMs: 1,
        usage: {
          inputTokens: null,
          outputTokens: null,
          estimatedCostMicroUsd: null,
        },
      }),
  };

  await assert.rejects(
    new SupportGuidePhrasingService(consent, provider, configuration).phrase(
      request,
    ),
    ServiceUnavailableException,
  );
});

void test("rejects callers without the user role and invalid copy before consent", async () => {
  let consentChecks = 0;
  const consent: ConsentClient = {
    check: () => {
      consentChecks += 1;
      return Promise.resolve({ authorized: true, reason: "GRANTED" });
    },
  };
  const provider: SupportGuidePhrasingProvider = {
    phrase: () => Promise.reject(new Error("provider must not be called")),
  };
  const service = new SupportGuidePhrasingService(
    consent,
    provider,
    configuration,
  );

  await assert.rejects(
    service.phrase({
      ...request,
      principal: { ...request.principal, roles: ["SPECIALIST"] },
    }),
    UnauthorizedException,
  );
  await assert.rejects(
    service.phrase({ ...request, body: { approvedText: "", locale: "vi-VN" } }),
    BadRequestException,
  );
  assert.equal(consentChecks, 0);
});

void test("builds a bounded copy-editing prompt without granting Care authority", () => {
  const approvedText = "Nội dung Care đã duyệt.";
  const prompt = supportGuidePhrasingPrompt(approvedText);

  assert.equal(prompt.version, "support-guide-phrasing-v1");
  assert.match(prompt.system, /Do not add, remove, rank, or recommend/);
  assert.match(prompt.system, /Return only the requested structured object/);
  assert.match(prompt.user, new RegExp(approvedText));
  assert.equal(prompt.schema.additionalProperties, false);
  assert.deepEqual(prompt.schema.required, ["text"]);
});
