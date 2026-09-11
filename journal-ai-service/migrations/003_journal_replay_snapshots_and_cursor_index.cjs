const commandsMigration = require("./002_journal_mutation_commands.cjs");

const responseSchema = {
  oneOf: [
    {
      bsonType: "object",
      required: ["kind", "revision", "tags", "analysisState"],
      additionalProperties: false,
      properties: {
        kind: { enum: ["entry"] },
        revision: { bsonType: "int", minimum: 1, maximum: 200 },
        tags: {
          bsonType: "array",
          maxItems: 20,
          uniqueItems: true,
          items: { bsonType: "string", minLength: 1, maxLength: 40 },
        },
        analysisState: { enum: ["not_requested", "current", "stale"] },
      },
    },
    {
      bsonType: "object",
      required: ["kind"],
      additionalProperties: false,
      properties: { kind: { enum: ["tombstone"] } },
    },
  ],
};

const commandSchema = {
  bsonType: "object",
  required: ["keyHash", "fingerprint", "operation", "recordedAt"],
  additionalProperties: false,
  properties: {
    keyHash: { bsonType: "string", minLength: 43, maxLength: 64 },
    fingerprint: { bsonType: "string", minLength: 43, maxLength: 64 },
    operation: { enum: ["create", "revise", "delete"] },
    resultRevision: { bsonType: "int", minimum: 1 },
    response: responseSchema,
    recordedAt: { bsonType: "date" },
  },
};

const validator = {
  $jsonSchema: {
    ...commandsMigration.validator.$jsonSchema,
    properties: {
      ...commandsMigration.validator.$jsonSchema.properties,
      commands: {
        bsonType: "array",
        minItems: 1,
        maxItems: 201,
        items: commandSchema,
      },
    },
  },
};

const cursorIndex = {
  ownerAccountId: 1,
  deleted: 1,
  "cursor.sortOccurredAt": -1,
  "cursor.sortCreatedAt": -1,
  "cursor.entryId": 1,
};

const previousCursorIndex = {
  ownerAccountId: 1,
  "cursor.sortOccurredAt": -1,
  "cursor.sortCreatedAt": -1,
  "cursor.entryId": 1,
};

module.exports = {
  async up(db) {
    await db.command({
      collMod: "journal_entries",
      validator,
      validationLevel: "strict",
      validationAction: "error",
    });
    const collection = db.collection("journal_entries");
    await collection.dropIndex("journal_entries_owner_cursor_idx");
    await collection.createIndex(cursorIndex, {
      name: "journal_entries_owner_cursor_idx",
    });
  },

  async down(db) {
    const collection = db.collection("journal_entries");
    await collection.dropIndex("journal_entries_owner_cursor_idx");
    await collection.createIndex(previousCursorIndex, {
      name: "journal_entries_owner_cursor_idx",
    });
    await db.command({
      collMod: "journal_entries",
      validator: commandsMigration.validator,
      validationLevel: "strict",
      validationAction: "error",
    });
  },

  validator,
  cursorIndex,
};
