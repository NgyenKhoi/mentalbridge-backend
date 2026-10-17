const routingMigration = require("./007_entitlement_aware_model_routing.cjs");

const jobCollection = "longitudinal_analysis_jobs";
const resultCollection = "journal_longitudinal_analysis_results";
const uuidPattern =
  "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

const period = {
  bsonType: "object",
  additionalProperties: false,
  required: ["startAt", "endAt"],
  properties: {
    startAt: { bsonType: "date" },
    endAt: { bsonType: "date" },
  },
};

const sourceRevision = {
  bsonType: "object",
  additionalProperties: false,
  required: ["journalId", "journalRevision", "period", "occurredAt"],
  properties: {
    journalId: { bsonType: "string", pattern: uuidPattern },
    journalRevision: { bsonType: ["int", "long"], minimum: 1, maximum: 200 },
    period: { enum: ["PREVIOUS", "CURRENT"] },
    occurredAt: { bsonType: "date" },
  },
};

const dataCoverage = {
  bsonType: "object",
  additionalProperties: false,
  required: [
    "previousPeriodJournalEntryCount",
    "currentPeriodJournalEntryCount",
    "sufficientForComparison",
  ],
  properties: {
    previousPeriodJournalEntryCount: {
      bsonType: ["int", "long"],
      minimum: 0,
      maximum: 50,
    },
    currentPeriodJournalEntryCount: {
      bsonType: ["int", "long"],
      minimum: 0,
      maximum: 50,
    },
    sufficientForComparison: { bsonType: "bool" },
  },
};

const route = structuredClone(
  routingMigration.jobValidator.$jsonSchema.properties.route,
);
route.properties.workload.enum = ["LONGITUDINAL"];
route.properties.promptVersion = { enum: ["longitudinal-v1"] };

const jobValidator = {
  $jsonSchema: {
    bsonType: "object",
    additionalProperties: false,
    required: [
      "_id",
      "ownerAccountId",
      "keyHash",
      "fingerprint",
      "previousPeriod",
      "currentPeriod",
      "excludedJournalIds",
      "sourceJournalRevisions",
      "dataCoverage",
      "status",
      "attemptCount",
      "nextAttemptAt",
      "leaseOwner",
      "leaseExpiresAt",
      "terminalReason",
      "resultId",
      "route",
      "createdAt",
      "updatedAt",
      "completedAt",
    ],
    properties: {
      _id: { bsonType: "string", pattern: uuidPattern },
      ownerAccountId: { bsonType: "string", pattern: uuidPattern },
      keyHash: { bsonType: "string", minLength: 43, maxLength: 43 },
      fingerprint: { bsonType: "string", minLength: 43, maxLength: 43 },
      previousPeriod: period,
      currentPeriod: period,
      excludedJournalIds: {
        bsonType: "array",
        maxItems: 100,
        uniqueItems: true,
        items: { bsonType: "string", pattern: uuidPattern },
      },
      sourceJournalRevisions: {
        bsonType: "array",
        maxItems: 100,
        items: sourceRevision,
      },
      dataCoverage,
      status: { enum: ["QUEUED", "RUNNING", "SUCCEEDED", "FAILED"] },
      attemptCount: { bsonType: ["int", "long"], minimum: 0, maximum: 2 },
      nextAttemptAt: { bsonType: "date" },
      leaseOwner: { bsonType: ["string", "null"] },
      leaseExpiresAt: { bsonType: ["date", "null"] },
      terminalReason: {
        enum: [
          null,
          "CONSENT_REQUIRED",
          "CONSENT_REVOKED",
          "CONSENT_UNAVAILABLE",
          "ENTITLEMENT_UNAVAILABLE",
          "ENTITLEMENT_CHANGED",
          "AUTHORIZATION_CONTEXT_LOST",
          "SOURCE_REVISION_CHANGED",
          "SOURCE_DELETED",
          "PROVIDER_TIMEOUT",
          "PROVIDER_UNAVAILABLE",
          "INVALID_PROVIDER_RESULT",
          "INTERNAL_ERROR",
        ],
      },
      resultId: {
        bsonType: ["string", "null"],
        pattern: uuidPattern,
      },
      route,
      createdAt: { bsonType: "date" },
      updatedAt: { bsonType: "date" },
      completedAt: { bsonType: ["date", "null"] },
    },
  },
};

const signalArray = {
  bsonType: "array",
  maxItems: 12,
  items: { bsonType: "string", minLength: 1, maxLength: 64 },
};

const normalizedResult = {
  bsonType: "object",
  additionalProperties: false,
  required: [
    "contextSignals",
    "emotionIndicators",
    "recurringThemes",
    "changesComparedWithPreviousPeriod",
    "preferences",
    "barriers",
    "helpfulPatterns",
  ],
  properties: {
    contextSignals: signalArray,
    emotionIndicators: signalArray,
    recurringThemes: signalArray,
    changesComparedWithPreviousPeriod: {
      bsonType: "array",
      maxItems: 24,
      items: {
        bsonType: "object",
        additionalProperties: false,
        required: ["signal", "direction"],
        properties: {
          signal: { bsonType: "string", minLength: 1, maxLength: 64 },
          direction: {
            enum: [
              "MORE_FREQUENT",
              "LESS_FREQUENT",
              "SIMILAR",
              "INSUFFICIENT_DATA",
            ],
          },
        },
      },
    },
    preferences: signalArray,
    barriers: signalArray,
    helpfulPatterns: signalArray,
  },
};

const resultValidator = {
  $jsonSchema: {
    bsonType: "object",
    additionalProperties: false,
    required: [
      "_id",
      "analysisId",
      "jobId",
      "userId",
      "previousPeriod",
      "currentPeriod",
      "sourceJournalRevisions",
      "dataCoverage",
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
      "schemaVersion",
      "latencyMs",
      "inputTokens",
      "outputTokens",
      "estimatedCostMicroUsd",
      "result",
      "createdAt",
    ],
    properties: {
      _id: { bsonType: "string", pattern: uuidPattern },
      analysisId: { bsonType: "string", pattern: uuidPattern },
      jobId: { bsonType: "string", pattern: uuidPattern },
      userId: { bsonType: "string", pattern: uuidPattern },
      previousPeriod: period,
      currentPeriod: period,
      sourceJournalRevisions: {
        bsonType: "array",
        maxItems: 100,
        items: sourceRevision,
      },
      dataCoverage,
      workload: { enum: ["LONGITUDINAL"] },
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
      promptVersion: { enum: ["longitudinal-v1"] },
      schemaVersion: { bsonType: ["int", "long"], enum: [1] },
      latencyMs: { bsonType: ["int", "long"], minimum: 0 },
      inputTokens: { bsonType: ["int", "long", "null"], minimum: 0 },
      outputTokens: { bsonType: ["int", "long", "null"], minimum: 0 },
      estimatedCostMicroUsd: {
        bsonType: ["int", "long", "null"],
        minimum: 0,
      },
      result: normalizedResult,
      createdAt: { bsonType: "date" },
    },
  },
};

module.exports = {
  async up(db) {
    await db.createCollection(jobCollection, {
      validator: jobValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db.collection(jobCollection).createIndex(
      { ownerAccountId: 1, keyHash: 1 },
      {
        name: "longitudinal_analysis_jobs_owner_idempotency_unique_idx",
        unique: true,
      },
    );
    await db
      .collection(jobCollection)
      .createIndex(
        { status: 1, nextAttemptAt: 1, leaseExpiresAt: 1 },
        { name: "longitudinal_analysis_jobs_claim_idx" },
      );
    await db
      .collection(jobCollection)
      .createIndex(
        { ownerAccountId: 1, createdAt: -1 },
        { name: "longitudinal_analysis_jobs_owner_created_idx" },
      );
    await db
      .collection(jobCollection)
      .createIndex(
        { ownerAccountId: 1, "sourceJournalRevisions.journalId": 1 },
        { name: "longitudinal_analysis_jobs_source_idx" },
      );

    await db.createCollection(resultCollection, {
      validator: resultValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db.collection(resultCollection).createIndex(
      { analysisId: 1 },
      {
        name: "journal_longitudinal_results_analysis_unique_idx",
        unique: true,
      },
    );
    await db
      .collection(resultCollection)
      .createIndex(
        { jobId: 1 },
        { name: "journal_longitudinal_results_job_unique_idx", unique: true },
      );
    await db
      .collection(resultCollection)
      .createIndex(
        { userId: 1, "currentPeriod.endAt": -1 },
        { name: "journal_longitudinal_results_user_period_idx" },
      );
    await db
      .collection(resultCollection)
      .createIndex(
        { userId: 1, "sourceJournalRevisions.journalId": 1 },
        { name: "journal_longitudinal_results_source_idx" },
      );
  },

  async down(db) {
    if (
      (await db.collection(jobCollection).estimatedDocumentCount()) > 0 ||
      (await db.collection(resultCollection).estimatedDocumentCount()) > 0
    ) {
      throw new Error(
        "Cannot remove longitudinal analysis collections after data has been written",
      );
    }
    await db.collection(resultCollection).drop();
    await db.collection(jobCollection).drop();
  },

  jobCollection,
  resultCollection,
  jobValidator,
  resultValidator,
};
