const collectionName = "journal_entries";

const dateSchema = {
  bsonType: "date",
};

const uuidStringSchema = {
  bsonType: "string",
  pattern:
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
};

const encryptedPayloadSchema = {
  bsonType: "object",
  required: ["ciphertext", "iv", "tag", "algorithm", "keyId", "encryptedAt"],
  additionalProperties: false,
  properties: {
    ciphertext: {
      bsonType: "binData",
      description: "Encrypted journal plaintext. Never index or log decrypted content.",
    },
    iv: {
      bsonType: "binData",
    },
    tag: {
      bsonType: "binData",
    },
    algorithm: {
      enum: ["AES-256-GCM"],
    },
    keyId: {
      bsonType: "string",
      minLength: 1,
    },
    encryptedAt: dateSchema,
  },
};

const revisionSchema = {
  bsonType: "object",
  required: [
    "revision",
    "createdAt",
    "content",
    "contentByteLength",
    "contentHash",
    "analysisInvalidatedAt",
  ],
  additionalProperties: false,
  properties: {
    revision: {
      bsonType: "int",
      minimum: 1,
    },
    createdAt: dateSchema,
    content: encryptedPayloadSchema,
    contentPreview: {
      bsonType: "string",
      maxLength: 160,
      description: "Safe preview only after product approval; omit when unavailable.",
    },
    contentByteLength: {
      bsonType: "int",
      minimum: 1,
      maximum: 65536,
    },
    contentHash: {
      bsonType: "string",
      minLength: 44,
      maxLength: 128,
      description: "Keyed digest for duplicate detection. Not raw plaintext.",
    },
    analysisInvalidatedAt: {
      bsonType: ["date", "null"],
    },
  },
};

const validator = {
  $jsonSchema: {
    bsonType: "object",
    required: [
      "_id",
      "ownerAccountId",
      "clientEntryId",
      "currentRevision",
      "occurredAt",
      "createdAt",
      "updatedAt",
      "deleted",
      "analysisState",
      "revisions",
      "cursor",
    ],
    additionalProperties: false,
    properties: {
      _id: uuidStringSchema,
      ownerAccountId: uuidStringSchema,
      clientEntryId: uuidStringSchema,
      currentRevision: {
        bsonType: "int",
        minimum: 1,
      },
      occurredAt: dateSchema,
      createdAt: dateSchema,
      updatedAt: dateSchema,
      deleted: {
        bsonType: "bool",
      },
      deletedAt: {
        bsonType: ["date", "null"],
      },
      deletedBy: uuidStringSchema,
      tombstoneReason: {
        enum: ["owner_deleted", "retention_expired", null],
      },
      tags: {
        bsonType: "array",
        maxItems: 20,
        uniqueItems: true,
        items: {
          bsonType: "string",
          minLength: 1,
          maxLength: 40,
        },
      },
      analysisState: {
        enum: ["not_requested", "current", "stale"],
      },
      revisions: {
        bsonType: "array",
        minItems: 1,
        maxItems: 200,
        items: revisionSchema,
      },
      cursor: {
        bsonType: "object",
        required: ["sortOccurredAt", "sortCreatedAt", "entryId"],
        additionalProperties: false,
        properties: {
          sortOccurredAt: dateSchema,
          sortCreatedAt: dateSchema,
          entryId: uuidStringSchema,
        },
      },
    },
  },
};

module.exports = {
  async up(db) {
    const collections = await db
      .listCollections({ name: collectionName }, { nameOnly: true })
      .toArray();

    if (collections.length === 0) {
      await db.createCollection(collectionName, {
        validator,
        validationLevel: "strict",
        validationAction: "error",
      });
    } else {
      await db.command({
        collMod: collectionName,
        validator,
        validationLevel: "strict",
        validationAction: "error",
      });
    }

    const collection = db.collection(collectionName);

    await collection.createIndex(
      {
        ownerAccountId: 1,
        "cursor.sortOccurredAt": -1,
        "cursor.sortCreatedAt": -1,
        "cursor.entryId": 1,
      },
      {
        name: "journal_entries_owner_cursor_idx",
      },
    );

    await collection.createIndex(
      {
        ownerAccountId: 1,
        clientEntryId: 1,
      },
      {
        name: "journal_entries_owner_client_entry_unique_idx",
        unique: true,
      },
    );

    await collection.createIndex(
      {
        ownerAccountId: 1,
        deleted: 1,
        updatedAt: -1,
      },
      {
        name: "journal_entries_owner_deleted_updated_idx",
      },
    );
  },

  async down(db) {
    await db.collection(collectionName).drop();
  },

  validator,
  collectionName,
};
