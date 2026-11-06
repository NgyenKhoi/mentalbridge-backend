const suggestions = require("./017_companion_message_suggestion.cjs");

const conversationCollection = "ai_companion_conversations";
const conversationValidator = structuredClone(
  suggestions.conversationValidator,
);
conversationValidator.$jsonSchema.properties.encryptedTitle = structuredClone(
  conversationValidator.$jsonSchema.properties.messages.items.properties
    .content,
);

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
    const titlesStored = await db
      .collection(conversationCollection)
      .countDocuments({ encryptedTitle: { $exists: true } });
    if (titlesStored > 0)
      throw new Error(
        "Cannot remove encrypted conversation titles after titles have been written",
      );
    await db.command({
      collMod: conversationCollection,
      validator: suggestions.conversationValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
  },
};
