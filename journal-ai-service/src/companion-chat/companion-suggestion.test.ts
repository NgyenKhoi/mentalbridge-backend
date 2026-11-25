import assert from "node:assert/strict";
import test from "node:test";
import { providerOutputSchemaV2 } from "./companion-chat.js";
import {
  SUGGESTION_DESTINATIONS,
  resolveSuggestion,
} from "./companion-suggestion.js";

const catalog = [
  {
    ref: "R1",
    resourceId: "00000000-0000-4000-8000-000000000001",
    category: "BREATHING",
    title: "Thở 4-7-8",
  },
];

void test("RESOURCE with a known ref resolves the real id and title", () => {
  assert.deepEqual(
    resolveSuggestion({ destination: "RESOURCE", resourceRef: "R1" }, catalog),
    {
      destination: "RESOURCE",
      resourceId: "00000000-0000-4000-8000-000000000001",
      resourceTitle: "Thở 4-7-8",
    },
  );
});

void test("invalid suggestions resolve to null", () => {
  for (const raw of [
    { destination: "RESOURCE", resourceRef: "R0" },
    { destination: "RESOURCE", resourceRef: "R31" },
    { destination: "RESOURCE", resourceRef: "r1" },
    { destination: "RESOURCE", resourceRef: null },
    { destination: "JOURNAL", resourceRef: "R1" },
    { destination: "SETTINGS", resourceRef: null },
    null,
    undefined,
  ])
    assert.equal(resolveSuggestion(raw, catalog), null);
});

void test("non-resource destinations resolve without id or title", () => {
  for (const destination of SUGGESTION_DESTINATIONS.filter(
    (value) => value !== "RESOURCE",
  ))
    assert.deepEqual(
      resolveSuggestion({ destination, resourceRef: null }, []),
      { destination, resourceId: null, resourceTitle: null },
    );
});

void test("v2 output schema accepts a null suggestion and rejects extra keys", () => {
  const base = {
    message: "Mình ở đây nè.",
    language: "vi",
    authority: "SUPPORT_ONLY",
    clinicalAssessment: "NONE",
    safetyDecision: "NONE",
    eligibilityDecision: "NONE",
    businessAction: "NONE",
    thirdPartyAction: "NONE",
  };
  assert.equal(
    providerOutputSchemaV2.safeParse({ ...base, suggestion: null }).success,
    true,
  );
  assert.equal(
    providerOutputSchemaV2.safeParse({
      ...base,
      suggestion: { destination: "JOURNAL", resourceRef: null },
    }).success,
    true,
  );
  assert.equal(providerOutputSchemaV2.safeParse(base).success, false);
  assert.equal(
    providerOutputSchemaV2.safeParse({
      ...base,
      suggestion: { destination: "JOURNAL", resourceRef: null, href: "/x" },
    }).success,
    false,
  );
});
