const { randomUUID } = require("node:crypto");

const sessionCollection = "ai_voice_sessions";
const usageCollection = "ai_voice_daily_usage";
const capacityCollection = "ai_voice_capacity";
const uuid = {
  bsonType: "string",
  pattern:
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
};
const integer = (maximum) => ({
  bsonType: ["int", "long"],
  minimum: 0,
  maximum,
});
const date = { bsonType: "date" };
const nullableDate = { bsonType: ["date", "null"] };
const nullableUuid = { ...uuid, bsonType: ["string", "null"] };
const digest = { bsonType: "string", pattern: "^[A-Za-z0-9_-]{43}$" };
const closed = (properties, required = Object.keys(properties)) => ({
  bsonType: "object",
  additionalProperties: false,
  required,
  properties,
});

const sessionValidator = {
  $jsonSchema: closed({
    _id: uuid,
    voiceSessionId: uuid,
    ownerId: uuid,
    keyHash: digest,
    fingerprint: digest,
    state: {
      enum: [
        "REQUESTED",
        "AUTHORIZED",
        "CONNECTING",
        "ACTIVE",
        "RECONNECTING",
        "ENDING",
        "ENDED",
        "FAILED",
        "EXPIRED",
      ],
    },
    policyVersion: { enum: ["companion-voice-policy-v1"] },
    entitlement: closed({
      packageCode: { enum: ["PLUS", "PREMIUM"] },
      source: { enum: ["DEMO", "PAID"] },
      entitlementVersion: { bsonType: "string", minLength: 1, maxLength: 128 },
      checkedAt: date,
      validUntil: date,
    }),
    quotaDay: { bsonType: "string", pattern: "^\\d{4}-\\d{2}-\\d{2}$" },
    admittedSeconds: { bsonType: ["int", "long"], minimum: 1, maximum: 600 },
    authorizedAt: date,
    authorityExpiresAt: date,
    hardStopAt: date,
    createdAt: date,
    updatedAt: date,
    expiresAt: date,
    deliveredSession: { bsonType: "bool" },
    chargedSeconds: integer(600),
    dispatchState: { enum: ["UNISSUED", "ISSUED", "STOPPED"] },
    capacityGeneration: uuid,
    attemptId: nullableUuid,
    leaseId: nullableUuid,
    leaseExpiresAt: nullableDate,
    lastSequence: integer(2_147_483_647),
    activeMilliseconds: integer(600_000),
    openActiveSince: nullableDate,
    lastActivityAt: date,
    reconnectDeadline: nullableDate,
    connectDeadline: nullableDate,
    terminalReason: {
      enum: [
        null,
        "USER_ENDED",
        "CONSENT_REVOKED",
        "VOICE_CONSENT_REVOKED",
        "ENTITLEMENT_LOST",
        "OWNER_UNAVAILABLE",
        "IDLE_TIMEOUT",
        "HARD_DEADLINE",
        "QUOTA_EXHAUSTED",
        "RECONNECT_TIMEOUT",
        "PROVIDER_FAILURE",
        "AGENT_FAILURE",
        "AUTHORIZATION_EXPIRED",
        "ACCOUNT_DELETED",
      ],
    },
    settledAt: nullableDate,
  }),
};

const usageValidator = {
  $jsonSchema: closed({
    _id: { bsonType: "string", minLength: 47, maxLength: 47 },
    ownerId: uuid,
    quotaDay: { bsonType: "string", pattern: "^\\d{4}-\\d{2}-\\d{2}$" },
    usedSessions: integer(3),
    reservedSessions: integer(1),
    usedSeconds: integer(1800),
    reservedSeconds: integer(600),
    startAttempts: integer(10),
    pendingAttempts: {
      bsonType: "array",
      maxItems: 10,
      uniqueItems: true,
      items: closed({ keyHash: digest, expiresAt: date }),
    },
    minuteStartedAt: date,
    minuteAttempts: integer(3),
    expiresAt: date,
  }),
};

const capacityValidator = {
  $jsonSchema: closed({
    _id: { enum: ["voice-global-v1"] },
    slots: {
      bsonType: "array",
      minItems: 2,
      maxItems: 2,
      items: {
        oneOf: [
          closed({
            status: { enum: ["FREE", "QUARANTINED"] },
            generation: uuid,
          }),
          closed({
            status: { enum: ["HELD"] },
            generation: uuid,
            ownerId: uuid,
            voiceSessionId: uuid,
          }),
        ],
      },
    },
  }),
};

const ensureCollection = async (db, name, validator) => {
  if (await db.listCollections({ name }, { nameOnly: true }).hasNext())
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
  sessionCollection,
  usageCollection,
  capacityCollection,
  sessionValidator,
  usageValidator,
  capacityValidator,
  async up(db) {
    await ensureCollection(db, sessionCollection, sessionValidator);
    await ensureCollection(db, usageCollection, usageValidator);
    await ensureCollection(db, capacityCollection, capacityValidator);
    await db
      .collection(sessionCollection)
      .createIndex(
        { ownerId: 1, keyHash: 1 },
        { name: "uq_ai_voice_owner_key", unique: true },
      );
    await db
      .collection(sessionCollection)
      .createIndex({ expiresAt: 1 }, { name: "idx_ai_voice_retention_sweep" });
    await db
      .collection(sessionCollection)
      .createIndex(
        { dispatchState: 1, state: 1, leaseExpiresAt: 1 },
        { name: "idx_ai_voice_reauthorization" },
      );
    await db
      .collection(usageCollection)
      .createIndex(
        { ownerId: 1, quotaDay: 1 },
        { name: "uq_ai_voice_owner_day", unique: true },
      );
    await db.collection(usageCollection).createIndex(
      { ownerId: 1, "pendingAttempts.keyHash": 1 },
      {
        name: "uq_ai_voice_pending_owner_key",
        unique: true,
        partialFilterExpression: {
          "pendingAttempts.keyHash": { $exists: true },
        },
      },
    );
    await db
      .collection(usageCollection)
      .createIndex(
        { expiresAt: 1 },
        { name: "ttl_ai_voice_daily_usage", expireAfterSeconds: 0 },
      );
    await db.collection(capacityCollection).updateOne(
      { _id: "voice-global-v1" },
      {
        $setOnInsert: {
          slots: Array.from({ length: 2 }, () => ({
            status: "FREE",
            generation: randomUUID(),
          })),
        },
      },
      { upsert: true },
    );
  },
  async down(db) {
    for (const name of [capacityCollection, usageCollection, sessionCollection])
      if (await db.listCollections({ name }, { nameOnly: true }).hasNext())
        await db.collection(name).drop();
  },
};
