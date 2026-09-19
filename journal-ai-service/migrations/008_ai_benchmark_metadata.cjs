const routing = require("./007_entitlement_aware_model_routing.cjs");

const datasetCollection = "benchmark_datasets";
const runCollection = "benchmark_runs";
const caseResultCollection = "benchmark_case_results";
const provider = { enum: ["GEMINI", "OPENAI"] };
const boundedModel = { bsonType: "string", minLength: 1, maxLength: 128 };
const nonNegativeIntegerOrNull = {
  bsonType: ["int", "long", "null"],
  minimum: 0,
};

const datasetValidator = {
  $jsonSchema: {
    bsonType: "object",
    required: [
      "_id",
      "datasetId",
      "version",
      "workload",
      "language",
      "source",
      "license",
      "sha256",
      "caseCount",
      "registeredAt",
    ],
    additionalProperties: false,
    properties: {
      _id: { bsonType: "string", minLength: 3, maxLength: 130 },
      datasetId: { bsonType: "string", minLength: 1, maxLength: 96 },
      version: { bsonType: "string", minLength: 1, maxLength: 32 },
      workload: { enum: ["EXACT_REVISION"] },
      language: { enum: ["vi"] },
      source: { enum: ["MENTALBRIDGE_SYNTHETIC_V1"] },
      license: { enum: ["CC0-1.0"] },
      sha256: { bsonType: "string", pattern: "^[0-9a-f]{64}$" },
      caseCount: { bsonType: "int", minimum: 1, maximum: 200 },
      registeredAt: { bsonType: "date" },
    },
  },
};

const candidate = {
  bsonType: "object",
  required: [
    "provider",
    "model",
    "inputCostMicroUsdPerMillionTokens",
    "outputCostMicroUsdPerMillionTokens",
  ],
  additionalProperties: false,
  properties: {
    provider,
    model: boundedModel,
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

const aggregate = {
  bsonType: "object",
  required: [
    "provider",
    "model",
    "caseCount",
    "successCount",
    "errorCount",
    "qualityPassRate",
    "safetyPassRate",
    "averageQualityScore",
    "averageLatencyMs",
    "totalInputTokens",
    "totalOutputTokens",
    "totalEstimatedCostMicroUsd",
  ],
  additionalProperties: false,
  properties: {
    provider,
    model: boundedModel,
    caseCount: { bsonType: "int", minimum: 1, maximum: 200 },
    successCount: { bsonType: "int", minimum: 0, maximum: 200 },
    errorCount: { bsonType: "int", minimum: 0, maximum: 200 },
    qualityPassRate: { bsonType: ["double", "int"], minimum: 0, maximum: 1 },
    safetyPassRate: { bsonType: ["double", "int"], minimum: 0, maximum: 1 },
    averageQualityScore: {
      bsonType: ["double", "int"],
      minimum: 0,
      maximum: 1,
    },
    averageLatencyMs: nonNegativeIntegerOrNull,
    totalInputTokens: { bsonType: ["int", "long"], minimum: 0 },
    totalOutputTokens: { bsonType: ["int", "long"], minimum: 0 },
    totalEstimatedCostMicroUsd: {
      bsonType: ["int", "long"],
      minimum: 0,
    },
  },
};

const runValidator = {
  $jsonSchema: {
    bsonType: "object",
    required: [
      "_id",
      "datasetId",
      "datasetVersion",
      "datasetSha256",
      "promptVersion",
      "schemaVersion",
      "status",
      "candidates",
      "startedAt",
      "completedAt",
    ],
    additionalProperties: false,
    properties: {
      _id: { bsonType: "string", pattern: "^[0-9a-fA-F-]{36}$" },
      datasetId: { bsonType: "string", minLength: 1, maxLength: 96 },
      datasetVersion: { bsonType: "string", minLength: 1, maxLength: 32 },
      datasetSha256: { bsonType: "string", pattern: "^[0-9a-f]{64}$" },
      promptVersion: { bsonType: "string", minLength: 1, maxLength: 96 },
      schemaVersion: { bsonType: "int", enum: [1] },
      status: { enum: ["RUNNING", "COMPLETED", "FAILED"] },
      candidates: {
        bsonType: "array",
        minItems: 1,
        maxItems: 2,
        items: candidate,
      },
      aggregates: {
        bsonType: "array",
        minItems: 1,
        maxItems: 2,
        items: aggregate,
      },
      startedAt: { bsonType: "date" },
      completedAt: { bsonType: ["date", "null"] },
    },
  },
};

const normalizedOutput = structuredClone(
  routing.resultValidator.$jsonSchema.properties.result,
);
normalizedOutput.bsonType = ["object", "null"];

const caseResultValidator = {
  $jsonSchema: {
    bsonType: "object",
    required: [
      "_id",
      "runId",
      "caseId",
      "provider",
      "model",
      "promptVersion",
      "schemaVersion",
      "status",
      "errorClassification",
      "qualityScore",
      "qualityPassed",
      "safetyPassed",
      "matchedSignalTerms",
      "requiredSignalTerms",
      "latencyMs",
      "inputTokens",
      "outputTokens",
      "estimatedCostMicroUsd",
      "normalizedOutput",
      "createdAt",
    ],
    additionalProperties: false,
    properties: {
      _id: { bsonType: "string", pattern: "^[0-9a-fA-F-]{36}$" },
      runId: { bsonType: "string", pattern: "^[0-9a-fA-F-]{36}$" },
      caseId: { bsonType: "string", minLength: 1, maxLength: 64 },
      provider,
      model: boundedModel,
      promptVersion: { bsonType: "string", minLength: 1, maxLength: 96 },
      schemaVersion: { bsonType: "int", enum: [1] },
      status: { enum: ["SUCCEEDED", "FAILED"] },
      errorClassification: {
        bsonType: ["string", "null"],
        maxLength: 64,
      },
      qualityScore: {
        bsonType: ["double", "int", "null"],
        minimum: 0,
        maximum: 1,
      },
      qualityPassed: { bsonType: ["bool", "null"] },
      safetyPassed: { bsonType: ["bool", "null"] },
      matchedSignalTerms: nonNegativeIntegerOrNull,
      requiredSignalTerms: { bsonType: "int", minimum: 1, maximum: 100 },
      latencyMs: nonNegativeIntegerOrNull,
      inputTokens: nonNegativeIntegerOrNull,
      outputTokens: nonNegativeIntegerOrNull,
      estimatedCostMicroUsd: nonNegativeIntegerOrNull,
      normalizedOutput,
      createdAt: { bsonType: "date" },
    },
  },
};

module.exports = {
  async up(db) {
    await db.createCollection(datasetCollection, {
      validator: datasetValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db
      .collection(datasetCollection)
      .createIndex(
        { datasetId: 1, version: 1 },
        { name: "benchmark_datasets_identity_unique_idx", unique: true },
      );
    await db.createCollection(runCollection, {
      validator: runValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db
      .collection(runCollection)
      .createIndex(
        { datasetId: 1, datasetVersion: 1, startedAt: -1 },
        { name: "benchmark_runs_dataset_started_idx" },
      );
    await db.createCollection(caseResultCollection, {
      validator: caseResultValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db
      .collection(caseResultCollection)
      .createIndex(
        { runId: 1, caseId: 1, provider: 1, model: 1 },
        { name: "benchmark_case_result_unique_idx", unique: true },
      );
  },

  async down(db) {
    const counts = await Promise.all([
      db.collection(datasetCollection).estimatedDocumentCount(),
      db.collection(runCollection).estimatedDocumentCount(),
      db.collection(caseResultCollection).estimatedDocumentCount(),
    ]);
    if (counts.some((count) => count > 0))
      throw new Error(
        "Cannot remove AI benchmark collections after benchmark metadata has been written",
      );
    await db.collection(caseResultCollection).drop();
    await db.collection(runCollection).drop();
    await db.collection(datasetCollection).drop();
  },

  datasetCollection,
  runCollection,
  caseResultCollection,
  datasetValidator,
  runValidator,
  caseResultValidator,
};
