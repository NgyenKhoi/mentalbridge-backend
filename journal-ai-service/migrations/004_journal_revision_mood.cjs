const replayMigration = require("./003_journal_replay_snapshots_and_cursor_index.cjs");

const encryptedMoodSchema = {
  bsonType: "object",
  required: ["ciphertext", "iv", "tag", "algorithm", "keyId", "encryptedAt"],
  additionalProperties: false,
  properties: {
    ciphertext: { bsonType: "binData" },
    iv: { bsonType: "binData" },
    tag: { bsonType: "binData" },
    algorithm: { enum: ["AES-256-GCM"] },
    keyId: { bsonType: "string", minLength: 1 },
    encryptedAt: { bsonType: "date" },
  },
};

const previousRevision =
  replayMigration.validator.$jsonSchema.properties.revisions.items;

const validator = {
  $jsonSchema: {
    ...replayMigration.validator.$jsonSchema,
    properties: {
      ...replayMigration.validator.$jsonSchema.properties,
      revisions: {
        ...replayMigration.validator.$jsonSchema.properties.revisions,
        items: {
          ...previousRevision,
          properties: {
            ...previousRevision.properties,
            mood: encryptedMoodSchema,
          },
        },
      },
    },
  },
};

module.exports = {
  async up(db) {
    await db.command({
      collMod: "journal_entries",
      validator,
      validationLevel: "strict",
      validationAction: "error",
    });
  },

  async down(db) {
    const entriesWithMood = await db
      .collection("journal_entries")
      .countDocuments({ "revisions.mood": { $exists: true } }, { limit: 1 });
    if (entriesWithMood > 0) {
      throw new Error(
        "Cannot remove encrypted journal mood after it has been written",
      );
    }
    await db.command({
      collMod: "journal_entries",
      validator: replayMigration.validator,
      validationLevel: "strict",
      validationAction: "error",
    });
  },

  validator,
};
