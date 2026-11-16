const benchmark = require("./008_ai_benchmark_metadata.cjs");
const governance = require("./019_benchmark_dataset_governance.cjs");

const approvalCollection = "benchmark_dataset_approvals";
const provider = { enum: ["GEMINI", "OPENAI", "BEDROCK"] };
const approvalValidator = {
  $jsonSchema: {
    bsonType: "object",
    required: [
      "_id",
      "registry",
      "approvalId",
      "approvedBy",
      "approvedAt",
      "contentSha256",
    ],
    additionalProperties: false,
    properties: {
      _id: { bsonType: "string", pattern: "^[0-9a-fA-F-]{36}$" },
      registry: { enum: ["MB369_CURATED_REGISTRY"] },
      approvalId: { bsonType: "string", pattern: "^[0-9a-fA-F-]{36}$" },
      approvedBy: { bsonType: "string", minLength: 1, maxLength: 128 },
      approvedAt: { bsonType: "string", minLength: 20, maxLength: 40 },
      contentSha256: { bsonType: "string", pattern: "^[0-9a-f]{64}$" },
    },
  },
};
const datasetValidator = structuredClone(governance.datasetValidator);
datasetValidator.$jsonSchema.properties.activeRunCount = {
  bsonType: "int",
  minimum: 0,
};
datasetValidator.$jsonSchema.properties.privacyApproval = {
  bsonType: "object",
  required: [
    "registry",
    "approvalId",
    "approvedBy",
    "approvedAt",
    "contentSha256",
  ],
  additionalProperties: false,
  properties: {
    registry: { enum: ["MB369_CURATED_REGISTRY"] },
    approvalId: { bsonType: "string", pattern: "^[0-9a-fA-F-]{36}$" },
    approvedBy: { bsonType: "string", minLength: 1, maxLength: 128 },
    approvedAt: { bsonType: "string", minLength: 20, maxLength: 40 },
    contentSha256: { bsonType: "string", pattern: "^[0-9a-f]{64}$" },
  },
};

const runValidator = structuredClone(benchmark.runValidator);
runValidator.$jsonSchema.required.push(
  "rubricVersion",
  "rubricSha256",
  "failureClassification",
);
runValidator.$jsonSchema.properties.rubricVersion = {
  bsonType: "string",
  minLength: 1,
  maxLength: 96,
};
runValidator.$jsonSchema.properties.rubricSha256 = {
  bsonType: "string",
  pattern: "^[0-9a-f]{64}$",
};
runValidator.$jsonSchema.properties.failureClassification = {
  enum: [null, "ALL_CASES_PROVIDER_FAILED", "EXECUTION_FAILED"],
};
runValidator.$jsonSchema.properties.candidates.maxItems = 3;
runValidator.$jsonSchema.properties.candidates.items.properties.provider =
  provider;
runValidator.$jsonSchema.properties.aggregates.maxItems = 3;
runValidator.$jsonSchema.properties.aggregates.items.properties.provider =
  provider;

const caseResultValidator = structuredClone(benchmark.caseResultValidator);
caseResultValidator.$jsonSchema.required.push("rubricVersion", "rubricSha256");
caseResultValidator.$jsonSchema.properties.provider = provider;
caseResultValidator.$jsonSchema.properties.rubricVersion = {
  bsonType: "string",
  minLength: 1,
  maxLength: 96,
};
caseResultValidator.$jsonSchema.properties.rubricSha256 = {
  bsonType: "string",
  pattern: "^[0-9a-f]{64}$",
};

module.exports = {
  async up(db) {
    await db.createCollection(approvalCollection, {
      validator: approvalValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db.command({
      collMod: benchmark.datasetCollection,
      validator: datasetValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db.command({
      collMod: benchmark.runCollection,
      validator: runValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db.command({
      collMod: benchmark.caseResultCollection,
      validator: caseResultValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
  },

  async down(db) {
    if ((await db.collection(approvalCollection).estimatedDocumentCount()) > 0)
      throw new Error(
        "Cannot remove the benchmark approval registry while approvals exist",
      );
    await db.collection(approvalCollection).drop();
    await db.command({
      collMod: benchmark.caseResultCollection,
      validator: benchmark.caseResultValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db.command({
      collMod: benchmark.runCollection,
      validator: benchmark.runValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db.command({
      collMod: benchmark.datasetCollection,
      validator: governance.datasetValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
  },

  datasetValidator,
  runValidator,
  caseResultValidator,
  approvalCollection,
  approvalValidator,
};
