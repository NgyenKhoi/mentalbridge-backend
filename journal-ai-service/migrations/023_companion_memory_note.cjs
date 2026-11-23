const titles = require("./018_companion_conversation_title.cjs");

const conversationCollection = "ai_companion_conversations";

// MB-846c: an optional encrypted memory note per conversation, read by later
// Mầm TEXT conversations only with effective L2 (ADR 0050 amendment).
const conversationValidator = structuredClone(titles.conversationValidator);
const properties = conversationValidator.$jsonSchema.properties;
properties.memoryNote = structuredClone(
  properties.messages.items.properties.content,
);
properties.memoryNoteUpdatedAt = { bsonType: "date" };
properties.memoryNoteVersion = {
  bsonType: "string",
  minLength: 1,
  maxLength: 64,
};

module.exports = {
  conversationValidator,
  async up(db) {
    await db.command({
      collMod: conversationCollection,
      validator: conversationValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
  },
  async down(db) {
    const notesStored = await db
      .collection(conversationCollection)
      .countDocuments({ memoryNote: { $exists: true } });
    if (notesStored > 0)
      throw new Error(
        "Cannot remove conversation memory notes after notes have been written",
      );
    await db.command({
      collMod: conversationCollection,
      validator: titles.conversationValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
  },
};
