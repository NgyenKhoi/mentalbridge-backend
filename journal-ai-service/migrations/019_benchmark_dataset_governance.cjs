const benchmark = require("./008_ai_benchmark_metadata.cjs");

const commandCollection = "benchmark_admin_commands";
const datasetValidator = structuredClone(benchmark.datasetValidator);

datasetValidator.$jsonSchema.properties.description = {
  bsonType: "string",
  minLength: 1,
  maxLength: 300,
};
datasetValidator.$jsonSchema.properties.createdBy = {
  bsonType: "string",
  minLength: 1,
  maxLength: 128,
};
datasetValidator.$jsonSchema.properties.archivedAt = {
  bsonType: ["date", "null"],
};
datasetValidator.$jsonSchema.properties.previousVersion = {
  bsonType: ["string", "null"],
  minLength: 1,
  maxLength: 32,
};
datasetValidator.$jsonSchema.properties.cases = {
  bsonType: "array",
  minItems: 1,
  maxItems: 200,
  items: {
    bsonType: "object",
    required: [
      "id",
      "text",
      "acceptableSuggestedActions",
      "requiredSignalTerms",
      "forbiddenClaims",
    ],
    additionalProperties: false,
    properties: {
      id: { bsonType: "string", pattern: "^[a-z0-9-]{1,64}$" },
      text: { bsonType: "string", minLength: 1, maxLength: 10000 },
      acceptableSuggestedActions: {
        bsonType: "array",
        minItems: 1,
        items: {
          enum: [
            "NONE",
            "OFFER_RESOURCE_EXPLANATION",
            "GUIDE_APPROVED_ACTIVITY",
            "REQUEST_ALLOWED_ALTERNATIVE",
            "REQUEST_PLAN_REVIEW",
            "OPEN_PROFESSIONAL_SUPPORT",
            "OPEN_SAFETY_GUIDANCE",
          ],
        },
      },
      requiredSignalTerms: {
        bsonType: "array",
        minItems: 1,
        items: { bsonType: "string", minLength: 1, maxLength: 64 },
      },
      forbiddenClaims: {
        bsonType: "array",
        items: { bsonType: "string", minLength: 1, maxLength: 128 },
      },
    },
  },
};

const commandValidator = {
  $jsonSchema: {
    bsonType: "object",
    required: ["_id", "actorId", "operation", "key", "fingerprint", "state"],
    additionalProperties: false,
    properties: {
      _id: { bsonType: "string", minLength: 1, maxLength: 300 },
      actorId: { bsonType: "string", minLength: 1, maxLength: 128 },
      operation: { bsonType: "string", minLength: 1, maxLength: 64 },
      key: {
        bsonType: "string",
        pattern: "^[0-9a-fA-F-]{36}$",
      },
      fingerprint: { bsonType: "string", pattern: "^[0-9a-f]{64}$" },
      state: { enum: ["PENDING", "COMPLETED", "FAILED"] },
      response: { bsonType: "object" },
    },
  },
};

module.exports = {
  async up(db) {
    await db.command({
      collMod: benchmark.datasetCollection,
      validator: datasetValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db.createCollection(commandCollection, {
      validator: commandValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db
      .collection(commandCollection)
      .createIndex(
        { actorId: 1, operation: 1, key: 1 },
        { name: "benchmark_admin_command_identity_unique_idx", unique: true },
      );
  },

  async down(db) {
    if ((await db.collection(commandCollection).estimatedDocumentCount()) > 0)
      throw new Error(
        "Cannot remove benchmark governance after admin commands exist",
      );
    await db.collection(commandCollection).drop();
    await db.command({
      collMod: benchmark.datasetCollection,
      validator: benchmark.datasetValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
  },

  commandCollection,
  datasetValidator,
  commandValidator,
};
