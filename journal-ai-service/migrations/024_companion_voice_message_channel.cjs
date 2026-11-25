const memoryNotes = require("./023_companion_memory_note.cjs");

const conversationCollection = "ai_companion_conversations";

// Voice pairs written back to their source Text conversation carry
// channel VOICE and the voice session that spoke them (write-once fence).
const conversationValidator = structuredClone(
  memoryNotes.conversationValidator,
);
const messageProperties =
  conversationValidator.$jsonSchema.properties.messages.items.properties;
messageProperties.channel = { enum: ["VOICE"] };
messageProperties.voiceSessionId = structuredClone(messageProperties.messageId);

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
    const voiceStored = await db
      .collection(conversationCollection)
      .countDocuments({ "messages.channel": { $exists: true } });
    if (voiceStored > 0)
      throw new Error(
        "Cannot remove voice message channel after voice messages have been written",
      );
    await db.command({
      collMod: conversationCollection,
      validator: memoryNotes.conversationValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
  },
};
