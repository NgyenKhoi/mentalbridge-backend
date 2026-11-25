const legacy = require("./013_companion_voice_quota.cjs").sessionValidator;
const sessionValidator = structuredClone(legacy);
sessionValidator.$jsonSchema.properties.contextHandoffHash = {
  bsonType: "string",
  pattern: "^[A-Za-z0-9_-]{43}$",
};

module.exports = {
  sessionValidator,
  async up(db) {
    await db.command({
      collMod: "ai_voice_sessions",
      validator: sessionValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db.collection("ai_voice_sessions").createIndex(
      { contextHandoffHash: 1 },
      {
        name: "uq_ai_voice_context_handoff",
        unique: true,
        partialFilterExpression: { contextHandoffHash: { $exists: true } },
      },
    );
  },
  async down(db) {
    if (
      await db.collection("ai_voice_sessions").countDocuments({
        contextHandoffHash: { $exists: true },
      })
    )
      throw new Error(
        "Retain consumed Voice handoff bindings; rollback requires their normal retention purge",
      );
    await db
      .collection("ai_voice_sessions")
      .dropIndex("uq_ai_voice_context_handoff");
    await db.command({
      collMod: "ai_voice_sessions",
      validator: legacy,
      validationLevel: "strict",
      validationAction: "error",
    });
  },
};
