const legacy = require("./014_companion_voice_media.cjs").commandValidator;
const uuid = {
  bsonType: "string",
  pattern:
    "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
};
const digest = { bsonType: "string", pattern: "^[A-Za-z0-9_-]{43}$" };
const properties = {
  _id: digest,
  operation: { enum: ["turn"] },
  voiceSessionId: uuid,
  attemptId: uuid,
  turnId: uuid,
  sequence: { bsonType: ["int", "long"], minimum: 1, maximum: 2147483647 },
  contextId: uuid,
  fingerprint: digest,
  routeHash: digest,
  createdAt: { bsonType: "date" },
  expiresAt: { bsonType: "date" },
};
const turnSchema = {
  bsonType: "object",
  additionalProperties: false,
  required: Object.keys(properties),
  properties,
};
const commandValidator = {
  $jsonSchema: { oneOf: [legacy.$jsonSchema, turnSchema] },
};
module.exports = {
  commandValidator,
  async up(db) {
    await db.command({
      collMod: "ai_voice_media_commands",
      validator: commandValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
    const collection = db.collection("ai_voice_media_commands");
    for (const field of ["sequence", "turnId"]) {
      await collection.createIndex(
        { voiceSessionId: 1, [field]: 1 },
        {
          unique: true,
          partialFilterExpression: { operation: "turn" },
          name: `uq_ai_voice_turn_${field}`,
        },
      );
    }
  },
  async down(db) {
    if (
      await db
        .collection("ai_voice_media_commands")
        .countDocuments({ operation: "turn" })
    )
      throw new Error(
        "Voice turn fences must expire or be safely purged after shutdown before rollback",
      );
    for (const field of ["sequence", "turnId"])
      await db
        .collection("ai_voice_media_commands")
        .dropIndex(`uq_ai_voice_turn_${field}`);
    await db.command({
      collMod: "ai_voice_media_commands",
      validator: legacy,
      validationLevel: "strict",
      validationAction: "error",
    });
  },
};
