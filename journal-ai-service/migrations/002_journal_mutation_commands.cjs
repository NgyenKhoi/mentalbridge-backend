const baseline = require("./001_journal_entries_baseline.cjs");

const commandSchema = {
  bsonType: "object",
  required: ["keyHash", "fingerprint", "operation", "recordedAt"],
  additionalProperties: false,
  properties: {
    keyHash: { bsonType: "string", minLength: 43, maxLength: 64 },
    fingerprint: { bsonType: "string", minLength: 43, maxLength: 64 },
    operation: { enum: ["create", "revise", "delete"] },
    resultRevision: { bsonType: "int", minimum: 1 },
    recordedAt: { bsonType: "date" },
  },
};

const validator = {
  $jsonSchema: {
    ...baseline.validator.$jsonSchema,
    required: [...baseline.validator.$jsonSchema.required, "commands"],
    properties: {
      ...baseline.validator.$jsonSchema.properties,
      commands: {
        bsonType: "array",
        minItems: 1,
        maxItems: 32,
        items: commandSchema,
      },
    },
  },
};

module.exports = {
  async up(db) {
    await db.command({
      collMod: baseline.collectionName,
      validator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db
      .collection(baseline.collectionName)
      .createIndex(
        { ownerAccountId: 1, "commands.keyHash": 1 },
        { name: "journal_entries_owner_command_unique_idx", unique: true },
      );
  },
  async down(db) {
    await db
      .collection(baseline.collectionName)
      .dropIndex("journal_entries_owner_command_unique_idx");
    await db.command({
      collMod: baseline.collectionName,
      validator: baseline.validator,
      validationLevel: "strict",
      validationAction: "error",
    });
  },
  validator,
};
