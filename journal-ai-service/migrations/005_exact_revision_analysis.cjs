const jobCollection = "analysis_jobs";
const resultCollection = "journal_analysis_results";

const jobValidator = {
  $jsonSchema: {
    bsonType: "object",
    required: [
      "_id",
      "ownerAccountId",
      "journalId",
      "journalRevision",
      "keyHash",
      "fingerprint",
      "status",
      "attemptCount",
      "nextAttemptAt",
      "createdAt",
      "updatedAt",
    ],
    additionalProperties: false,
    properties: {
      _id: { bsonType: "string", pattern: "^[0-9a-fA-F-]{36}$" },
      ownerAccountId: { bsonType: "string", pattern: "^[0-9a-fA-F-]{36}$" },
      journalId: { bsonType: "string", pattern: "^[0-9a-fA-F-]{36}$" },
      journalRevision: { bsonType: "int", minimum: 1, maximum: 200 },
      keyHash: { bsonType: "string", minLength: 43, maxLength: 43 },
      fingerprint: { bsonType: "string", minLength: 43, maxLength: 43 },
      status: { enum: ["QUEUED", "RUNNING", "SUCCEEDED", "FAILED"] },
      attemptCount: { bsonType: "int", minimum: 0, maximum: 2 },
      nextAttemptAt: { bsonType: "date" },
      leaseOwner: { bsonType: ["string", "null"] },
      leaseExpiresAt: { bsonType: ["date", "null"] },
      terminalReason: {
        enum: [
          null,
          "CONSENT_REQUIRED",
          "CONSENT_REVOKED",
          "CONSENT_UNAVAILABLE",
          "AUTHORIZATION_CONTEXT_LOST",
          "REVISION_STALE",
          "JOURNAL_DELETED",
          "PROVIDER_TIMEOUT",
          "PROVIDER_UNAVAILABLE",
          "INVALID_PROVIDER_RESULT",
          "INTERNAL_ERROR",
        ],
      },
      resultId: { bsonType: ["string", "null"] },
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

const resultValidator = {
  $jsonSchema: {
    bsonType: "object",
    required: [
      "_id",
      "analysisId",
      "jobId",
      "entryId",
      "userId",
      "journalRevision",
      "provider",
      "model",
      "promptVersion",
      "schemaVersion",
      "result",
      "createdAt",
    ],
    additionalProperties: false,
    properties: {
      _id: { bsonType: "string" },
      analysisId: { bsonType: "string" },
      jobId: { bsonType: "string" },
      entryId: { bsonType: "string" },
      userId: { bsonType: "string" },
      journalRevision: { bsonType: "int", minimum: 1, maximum: 200 },
      provider: { enum: ["DETERMINISTIC_FAKE"] },
      model: { enum: ["deterministic-reflection-v1"] },
      promptVersion: { enum: ["exact-revision-v1"] },
      schemaVersion: { bsonType: "int", enum: [1] },
      result: {
        bsonType: "object",
        required: [
          "contextSignals",
          "emotionIndicators",
          "themes",
          "preferenceSignals",
          "barrierSignals",
          "suggestedAction",
        ],
        additionalProperties: false,
        properties: {
          summary: { bsonType: "string", minLength: 1, maxLength: 800 },
          contextSignals: signalArray,
          emotionIndicators: signalArray,
          themes: signalArray,
          preferenceSignals: signalArray,
          barrierSignals: signalArray,
          sentiment: { bsonType: "string", minLength: 1, maxLength: 32 },
          modelConfidence: {
            bsonType: ["double", "int"],
            minimum: 0,
            maximum: 1,
          },
          suggestedAction: {
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
      },
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
    await db
      .collection(jobCollection)
      .createIndex(
        { ownerAccountId: 1, keyHash: 1 },
        { name: "analysis_jobs_owner_idempotency_unique_idx", unique: true },
      );
    await db
      .collection(jobCollection)
      .createIndex(
        { status: 1, nextAttemptAt: 1, leaseExpiresAt: 1 },
        { name: "analysis_jobs_claim_idx" },
      );
    await db
      .collection(jobCollection)
      .createIndex(
        { ownerAccountId: 1, createdAt: -1 },
        { name: "analysis_jobs_owner_created_idx" },
      );

    await db.createCollection(resultCollection, {
      validator: resultValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db
      .collection(resultCollection)
      .createIndex(
        { analysisId: 1 },
        { name: "journal_analysis_results_analysis_unique_idx", unique: true },
      );
    await db
      .collection(resultCollection)
      .createIndex(
        { jobId: 1 },
        { name: "journal_analysis_results_job_unique_idx", unique: true },
      );
    await db
      .collection(resultCollection)
      .createIndex(
        { userId: 1, entryId: 1, journalRevision: 1, createdAt: -1 },
        { name: "journal_analysis_results_source_idx" },
      );
  },

  async down(db) {
    if (
      (await db.collection(jobCollection).estimatedDocumentCount()) > 0 ||
      (await db.collection(resultCollection).estimatedDocumentCount()) > 0
    ) {
      throw new Error(
        "Cannot remove exact-revision analysis collections after data has been written",
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
