const collectionName = "emotion_check_ins";

const uuid = { bsonType: "string", pattern: "^[0-9a-fA-F-]{36}$" };
const encryptedPayload = {
  bsonType: "object",
  required: ["ciphertext", "iv", "tag", "algorithm", "keyId", "encryptedAt"],
  additionalProperties: false,
  properties: {
    ciphertext: { bsonType: "binData" },
    iv: { bsonType: "binData" },
    tag: { bsonType: "binData" },
    algorithm: { enum: ["AES-256-GCM"] },
    keyId: { bsonType: "string", minLength: 1, maxLength: 64 },
    encryptedAt: { bsonType: "date" },
  },
};

const validator = {
  $jsonSchema: {
    bsonType: "object",
    required: [
      "_id",
      "ownerAccountId",
      "localDate",
      "timezone",
      "currentRevision",
      "revisions",
      "commands",
      "createdAt",
      "updatedAt",
      "deleted",
      "deletedAt",
      "purgeAfter",
    ],
    additionalProperties: false,
    properties: {
      _id: uuid,
      ownerAccountId: uuid,
      localDate: {
        bsonType: "string",
        pattern: "^[0-9]{4}-[0-9]{2}-[0-9]{2}$",
      },
      timezone: { bsonType: "string", minLength: 1, maxLength: 64 },
      currentRevision: { bsonType: "int", minimum: 1, maximum: 32 },
      revisions: {
        bsonType: "array",
        maxItems: 32,
        items: {
          bsonType: "object",
          required: ["revision", "recordedAt", "payload"],
          additionalProperties: false,
          properties: {
            revision: { bsonType: "int", minimum: 1, maximum: 32 },
            recordedAt: { bsonType: "date" },
            payload: encryptedPayload,
          },
        },
      },
      commands: {
        bsonType: "array",
        minItems: 1,
        maxItems: 33,
        items: {
          bsonType: "object",
          required: [
            "keyHash",
            "fingerprint",
            "operation",
            "response",
            "recordedAt",
          ],
          additionalProperties: false,
          properties: {
            keyHash: { bsonType: "string", minLength: 43, maxLength: 43 },
            fingerprint: {
              bsonType: "string",
              minLength: 43,
              maxLength: 43,
            },
            operation: { enum: ["create", "update", "delete"] },
            response: {
              bsonType: "object",
              required: ["kind"],
              additionalProperties: false,
              properties: {
                kind: { enum: ["check-in", "tombstone"] },
                revision: { bsonType: "int", minimum: 1, maximum: 32 },
              },
            },
            recordedAt: { bsonType: "date" },
          },
        },
      },
      createdAt: { bsonType: "date" },
      updatedAt: { bsonType: "date" },
      deleted: { bsonType: "bool" },
      deletedAt: { bsonType: ["date", "null"] },
      purgeAfter: { bsonType: ["date", "null"] },
    },
  },
};

module.exports = {
  async up(db) {
    await db.createCollection(collectionName, {
      validator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db
      .collection(collectionName)
      .createIndex(
        { ownerAccountId: 1, localDate: 1 },
        { name: "emotion_check_ins_owner_day_unique_idx", unique: true },
      );
    await db
      .collection(collectionName)
      .createIndex(
        { ownerAccountId: 1, deleted: 1, localDate: -1 },
        { name: "emotion_check_ins_owner_history_idx" },
      );
    await db
      .collection(collectionName)
      .createIndex(
        { ownerAccountId: 1, "commands.keyHash": 1 },
        { name: "emotion_check_ins_owner_command_unique_idx", unique: true },
      );
    await db
      .collection(collectionName)
      .createIndex(
        { purgeAfter: 1 },
        { name: "emotion_check_ins_tombstone_ttl_idx", expireAfterSeconds: 0 },
      );
  },

  async down(db) {
    if (await db.collection(collectionName).estimatedDocumentCount()) {
      throw new Error(
        "Cannot remove daily emotion check-ins after data has been written",
      );
    }
    await db.collection(collectionName).drop();
  },

  collectionName,
  validator,
};
