const conversationCollection = "ai_companion_conversations";
const commandCollection = "ai_companion_commands";
const quotaCollection = "ai_companion_quota_ledgers";
const rateCollection = "ai_companion_rate_ledgers";
const uuidPattern =
  "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

const encryptedText = {
  bsonType: "object",
  additionalProperties: false,
  required: ["ciphertext", "iv", "tag", "algorithm", "keyId"],
  properties: {
    ciphertext: { bsonType: "binData" },
    iv: { bsonType: "binData" },
    tag: { bsonType: "binData" },
    algorithm: { enum: ["AES-256-GCM"] },
    keyId: { bsonType: "string", minLength: 1, maxLength: 96 },
  },
};

const route = {
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
  ],
  properties: {
    workload: { enum: ["COMPANION_CHAT"] },
    servicePlan: { enum: ["FREE", "PLUS", "PREMIUM"] },
    entitlementSource: { enum: ["DEFAULT_FREE", "DEMO", "PAID"] },
    entitlementPolicyVersion: {
      bsonType: "string",
      minLength: 1,
      maxLength: 96,
    },
    entitlementVersion: { bsonType: ["int", "long"], minimum: 0 },
    routingPolicyVersion: { bsonType: "string", minLength: 1, maxLength: 96 },
    providerApprovalVersion: {
      bsonType: "string",
      minLength: 1,
      maxLength: 96,
    },
    provider: { enum: ["DETERMINISTIC_FAKE", "GEMINI", "OPENAI"] },
    model: { bsonType: "string", minLength: 1, maxLength: 128 },
    promptVersion: { enum: ["companion-chat-v1"] },
  },
};

const message = {
  bsonType: "object",
  additionalProperties: false,
  required: [
    "messageId",
    "role",
    "content",
    "createdAt",
    "route",
    "contextKinds",
  ],
  properties: {
    messageId: { bsonType: "string", pattern: uuidPattern },
    role: { enum: ["USER", "ASSISTANT"] },
    content: encryptedText,
    createdAt: { bsonType: "date" },
    route,
    contextKinds: {
      bsonType: "array",
      maxItems: 3,
      uniqueItems: true,
      items: { enum: ["JOURNAL", "SUPPORT_PLAN", "REASSESSMENT"] },
    },
  },
};

const conversationValidator = {
  $jsonSchema: {
    bsonType: "object",
    additionalProperties: false,
    required: [
      "_id",
      "ownerAccountId",
      "title",
      "messages",
      "createdAt",
      "updatedAt",
      "expiresAt",
    ],
    properties: {
      _id: { bsonType: "string", pattern: uuidPattern },
      ownerAccountId: { bsonType: "string", pattern: uuidPattern },
      title: { bsonType: "string", minLength: 1, maxLength: 80 },
      messages: { bsonType: "array", maxItems: 400, items: message },
      createdAt: { bsonType: "date" },
      updatedAt: { bsonType: "date" },
      expiresAt: { bsonType: "date" },
    },
  },
};

const commandValidator = {
  $jsonSchema: {
    bsonType: "object",
    additionalProperties: false,
    required: [
      "_id",
      "ownerAccountId",
      "conversationId",
      "keyHash",
      "fingerprint",
      "state",
      "response",
      "failureCode",
      "failureStatus",
      "failureTitle",
      "createdAt",
      "updatedAt",
      "expiresAt",
    ],
    properties: {
      _id: { bsonType: "string", pattern: uuidPattern },
      ownerAccountId: { bsonType: "string", pattern: uuidPattern },
      conversationId: { bsonType: "string", pattern: uuidPattern },
      keyHash: { bsonType: "string", minLength: 43, maxLength: 43 },
      fingerprint: { bsonType: "string", minLength: 43, maxLength: 43 },
      state: { enum: ["RUNNING", "SUCCEEDED", "FAILED"] },
      response: { bsonType: ["object", "null"] },
      failureCode: { bsonType: ["string", "null"], maxLength: 96 },
      failureStatus: {
        bsonType: ["int", "long", "null"],
        minimum: 400,
        maximum: 599,
      },
      failureTitle: { bsonType: ["string", "null"], maxLength: 160 },
      createdAt: { bsonType: "date" },
      updatedAt: { bsonType: "date" },
      expiresAt: { bsonType: "date" },
    },
  },
};

const quotaValidator = {
  $jsonSchema: {
    bsonType: "object",
    additionalProperties: false,
    required: [
      "_id",
      "ownerAccountId",
      "localDate",
      "plan",
      "successfulAnswers",
      "usedTokens",
      "reservations",
      "expiresAt",
    ],
    properties: {
      _id: { bsonType: "string", minLength: 47, maxLength: 47 },
      ownerAccountId: { bsonType: "string", pattern: uuidPattern },
      localDate: { bsonType: "string", pattern: "^\\d{4}-\\d{2}-\\d{2}$" },
      plan: { enum: ["FREE", "PLUS", "PREMIUM"] },
      successfulAnswers: { bsonType: ["int", "long"], minimum: 0 },
      usedTokens: { bsonType: ["int", "long"], minimum: 0 },
      reservations: {
        bsonType: "array",
        maxItems: 60,
        uniqueItems: true,
        items: { bsonType: "string", pattern: uuidPattern },
      },
      expiresAt: { bsonType: "date" },
    },
  },
};

const rateValidator = {
  $jsonSchema: {
    bsonType: "object",
    additionalProperties: false,
    required: ["_id", "ownerAccountId", "minuteBucket", "count", "expiresAt"],
    properties: {
      _id: { bsonType: "string", minLength: 53, maxLength: 53 },
      ownerAccountId: { bsonType: "string", pattern: uuidPattern },
      minuteBucket: {
        bsonType: "string",
        pattern: "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$",
      },
      count: { bsonType: ["int", "long"], minimum: 0, maximum: 60 },
      expiresAt: { bsonType: "date" },
    },
  },
};

const ensureCollection = async (db, name, validator) => {
  const exists = await db
    .listCollections({ name }, { nameOnly: true })
    .hasNext();
  if (exists)
    await db.command({
      collMod: name,
      validator,
      validationLevel: "strict",
      validationAction: "error",
    });
  else
    await db.createCollection(name, {
      validator,
      validationLevel: "strict",
      validationAction: "error",
    });
};

module.exports = {
  conversationCollection,
  commandCollection,
  quotaCollection,
  rateCollection,
  conversationValidator,
  commandValidator,
  quotaValidator,
  rateValidator,
  async up(db) {
    await ensureCollection(db, conversationCollection, conversationValidator);
    await ensureCollection(db, commandCollection, commandValidator);
    await ensureCollection(db, quotaCollection, quotaValidator);
    await ensureCollection(db, rateCollection, rateValidator);
    await db
      .collection(conversationCollection)
      .createIndex(
        { ownerAccountId: 1, updatedAt: -1, _id: -1 },
        { name: "idx_ai_companion_owner_updated" },
      );
    await db
      .collection(conversationCollection)
      .createIndex(
        { expiresAt: 1 },
        { name: "ttl_ai_companion_conversation", expireAfterSeconds: 0 },
      );
    await db
      .collection(commandCollection)
      .createIndex(
        { ownerAccountId: 1, keyHash: 1 },
        { name: "uq_ai_companion_owner_key", unique: true },
      );
    await db
      .collection(commandCollection)
      .createIndex(
        { expiresAt: 1 },
        { name: "ttl_ai_companion_command", expireAfterSeconds: 0 },
      );
    await db
      .collection(quotaCollection)
      .createIndex(
        { expiresAt: 1 },
        { name: "ttl_ai_companion_quota", expireAfterSeconds: 0 },
      );
    await db
      .collection(rateCollection)
      .createIndex(
        { expiresAt: 1 },
        { name: "ttl_ai_companion_rate", expireAfterSeconds: 0 },
      );
  },
  async down(db) {
    for (const name of [
      rateCollection,
      quotaCollection,
      commandCollection,
      conversationCollection,
    ]) {
      if (await db.listCollections({ name }, { nameOnly: true }).hasNext())
        await db.collection(name).drop();
    }
  },
};
