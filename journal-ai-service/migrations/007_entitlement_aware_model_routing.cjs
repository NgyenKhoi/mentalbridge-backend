const baseline = require("./005_exact_revision_analysis.cjs");

const jobValidator = structuredClone(baseline.jobValidator);
jobValidator.$jsonSchema.properties.terminalReason.enum.splice(
  3,
  0,
  "ENTITLEMENT_UNAVAILABLE",
  "ENTITLEMENT_CHANGED",
);
jobValidator.$jsonSchema.properties.route = {
  bsonType: ["object", "null"],
  additionalProperties: false,
  required: [
    "workload",
    "servicePlan",
    "entitlementSource",
    "entitlementPolicyVersion",
    "entitlementVersion",
    "routingPolicyVersion",
    "providerApprovalVersion",
    "provider",
    "model",
    "promptVersion",
    "inputCostMicroUsdPerMillionTokens",
    "outputCostMicroUsdPerMillionTokens",
  ],
  properties: {
    workload: { enum: ["EXACT_REVISION"] },
    servicePlan: { enum: ["FREE", "PLUS", "PREMIUM"] },
    entitlementSource: { enum: ["DEFAULT_FREE", "DEMO", "PAID"] },
    entitlementPolicyVersion: {
      bsonType: "string",
      minLength: 1,
      maxLength: 96,
    },
    entitlementVersion: { bsonType: ["int", "long"], minimum: 0 },
    routingPolicyVersion: {
      bsonType: "string",
      minLength: 1,
      maxLength: 96,
    },
    providerApprovalVersion: {
      bsonType: "string",
      minLength: 1,
      maxLength: 96,
    },
    provider: { enum: ["DETERMINISTIC_FAKE", "GEMINI", "OPENAI"] },
    model: { bsonType: "string", minLength: 1, maxLength: 128 },
    promptVersion: { bsonType: "string", minLength: 1, maxLength: 96 },
    inputCostMicroUsdPerMillionTokens: {
      bsonType: ["int", "long"],
      minimum: 0,
    },
    outputCostMicroUsdPerMillionTokens: {
      bsonType: ["int", "long"],
      minimum: 0,
    },
  },
};

const resultValidator = structuredClone(baseline.resultValidator);
resultValidator.$jsonSchema.properties.provider = {
  enum: ["DETERMINISTIC_FAKE", "GEMINI", "OPENAI"],
};
resultValidator.$jsonSchema.properties.model = {
  bsonType: "string",
  minLength: 1,
  maxLength: 128,
};
resultValidator.$jsonSchema.properties.promptVersion = {
  bsonType: "string",
  minLength: 1,
  maxLength: 96,
};
Object.assign(resultValidator.$jsonSchema.properties, {
  workload: { enum: ["EXACT_REVISION"] },
  servicePlan: { enum: ["FREE", "PLUS", "PREMIUM"] },
  entitlementSource: { enum: ["DEFAULT_FREE", "DEMO", "PAID"] },
  entitlementPolicyVersion: {
    bsonType: "string",
    minLength: 1,
    maxLength: 96,
  },
  entitlementVersion: { bsonType: ["int", "long"], minimum: 0 },
  routingPolicyVersion: {
    bsonType: "string",
    minLength: 1,
    maxLength: 96,
  },
  providerApprovalVersion: {
    bsonType: "string",
    minLength: 1,
    maxLength: 96,
  },
  latencyMs: { bsonType: ["int", "long"], minimum: 0 },
  inputTokens: { bsonType: ["int", "long", "null"], minimum: 0 },
  outputTokens: { bsonType: ["int", "long", "null"], minimum: 0 },
  estimatedCostMicroUsd: {
    bsonType: ["int", "long", "null"],
    minimum: 0,
  },
});

module.exports = {
  async up(db) {
    await db.command({
      collMod: baseline.jobCollection,
      validator: jobValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db.command({
      collMod: baseline.resultCollection,
      validator: resultValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
  },

  async down(db) {
    const routedJobs = await db
      .collection(baseline.jobCollection)
      .countDocuments({ route: { $exists: true, $ne: null } });
    const routedResults = await db
      .collection(baseline.resultCollection)
      .countDocuments({ workload: { $exists: true } });
    if (routedJobs > 0 || routedResults > 0) {
      throw new Error(
        "Cannot remove entitlement-aware model routing validation after routed data has been written",
      );
    }
    await db.command({
      collMod: baseline.jobCollection,
      validator: baseline.jobValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db.command({
      collMod: baseline.resultCollection,
      validator: baseline.resultValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
  },

  jobValidator,
  resultValidator,
};
