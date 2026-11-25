const context = require("./012_ai_companion_conversation_context.cjs");

const conversationCollection = "ai_companion_conversations";

// ADR 0049: an assistant message may carry one resolved suggestion, encrypted
// with the same per-owner AES-GCM envelope as its content.
const conversationValidator = structuredClone(context.conversationValidator);
const messageProperties =
  conversationValidator.$jsonSchema.properties.messages.items.properties;
messageProperties.suggestion = structuredClone(messageProperties.content);

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
    const suggestionsStored = await db
      .collection(conversationCollection)
      .countDocuments({ "messages.suggestion": { $exists: true } });
    if (suggestionsStored > 0)
      throw new Error(
        "Cannot remove message suggestions after suggestions have been written",
      );
    await db.command({
      collMod: conversationCollection,
      validator: context.conversationValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
  },
};
