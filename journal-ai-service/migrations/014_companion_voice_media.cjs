const uuid = {
  bsonType: "string",
  pattern:
    "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
};
const nullableUuid = { ...uuid, bsonType: ["string", "null"] };
const date = { bsonType: "date" };
const nullableDate = { bsonType: ["date", "null"] };
const digest = { bsonType: "string", pattern: "^[A-Za-z0-9_-]{43}$" };
const integer = { bsonType: ["int", "long"], minimum: 0, maximum: 2147483647 };
const closed = (properties) => ({
  bsonType: "object",
  additionalProperties: false,
  required: Object.keys(properties),
  properties,
});
const timestamp = {
  bsonType: "string",
  pattern: "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z$",
};
const reasons = [
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
];
const envelope = {
  schemaVersion: { enum: [1] },
  commandId: uuid,
  voiceSessionId: uuid,
  attemptId: uuid,
  sequence: { ...integer, minimum: 1 },
  issuedAt: timestamp,
  expiresAt: timestamp,
};
const control = {
  oneOf: [
    { bsonType: "null" },
    closed({
      action: { enum: ["CONTINUE"] },
      control: closed({
        ...envelope,
        kind: { enum: ["LEASE_RENEWED"] },
        payload: closed({
          leaseId: uuid,
          validUntil: timestamp,
          hardStopAt: timestamp,
          admittedSeconds: { ...integer, minimum: 1, maximum: 600 },
        }),
      }),
    }),
    closed({
      action: { enum: ["STOP"] },
      control: closed({
        ...envelope,
        kind: { enum: ["STOP"] },
        payload: closed({ reason: { enum: reasons } }),
      }),
    }),
  ],
};
const mediaValidator = {
  $jsonSchema: closed({
    _id: uuid,
    voiceSessionId: uuid,
    attemptId: uuid,
    dispatchId: uuid,
    roomName: { bsonType: "string", pattern: "^ai-voice-[0-9a-f-]{36}$" },
    participantIdentity: { bsonType: "string", pattern: "^vu-[0-9a-f-]{36}$" },
    agentIdentity: { bsonType: "string", pattern: "^va-[0-9a-f-]{36}$" },
    hardStopAt: date,
    providerDispatchId: {
      bsonType: ["string", "null"],
      minLength: 1,
      maxLength: 128,
    },
    phase: {
      enum: [
        "NEW",
        "CREATING",
        "ROOM_READY",
        "DISPATCHING",
        "READY",
        "STOPPING",
        "STOPPED",
      ],
    },
    claimedAt: nullableDate,
    workerSequence: integer,
    workerFingerprint: { ...digest, bsonType: ["string", "null"] },
    backendSequence: integer,
    lastControl: control,
    expectedTurnId: nullableUuid,
    expectedReplyId: nullableUuid,
    createdAt: date,
    updatedAt: date,
    expiresAt: date,
  }),
};
const commandValidator = {
  $jsonSchema: closed({
    _id: digest,
    operation: { enum: ["connection", "end"] },
    voiceSessionId: uuid,
    createdAt: date,
    expiresAt: date,
  }),
};

const ensure = async (db, name, validator) => {
  if (await db.listCollections({ name }, { nameOnly: true }).hasNext()) {
    await db.command({
      collMod: name,
      validator,
      validationLevel: "strict",
      validationAction: "error",
    });
  } else {
    await db.createCollection(name, {
      validator,
      validationLevel: "strict",
      validationAction: "error",
    });
  }
};

module.exports = {
  mediaValidator,
  commandValidator,
  async up(db) {
    await ensure(db, "ai_voice_media", mediaValidator);
    await ensure(db, "ai_voice_media_commands", commandValidator);
    await db
      .collection("ai_voice_media")
      .createIndex(
        { roomName: 1 },
        { unique: true, name: "uq_ai_voice_media_room" },
      );
    await db
      .collection("ai_voice_media")
      .createIndex(
        { dispatchId: 1 },
        { unique: true, name: "uq_ai_voice_media_dispatch" },
      );
    await db
      .collection("ai_voice_media")
      .createIndex(
        { phase: 1, expiresAt: 1 },
        { name: "idx_ai_voice_media_reconciliation" },
      );
    await db
      .collection("ai_voice_media")
      .createIndex(
        { expiresAt: 1 },
        { name: "ttl_ai_voice_media", expireAfterSeconds: 0 },
      );
    await db
      .collection("ai_voice_media_commands")
      .createIndex(
        { expiresAt: 1 },
        { name: "ttl_ai_voice_media_commands", expireAfterSeconds: 0 },
      );
  },
  async down(db) {
    if (
      await db
        .collection("ai_voice_media")
        .countDocuments({ phase: { $ne: "STOPPED" } })
    )
      throw new Error("Stop and reconcile all Voice media before rollback");
    for (const name of ["ai_voice_media_commands", "ai_voice_media"]) {
      if (await db.listCollections({ name }, { nameOnly: true }).hasNext())
        await db.dropCollection(name);
    }
  },
};
