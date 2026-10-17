const bedrock = require("./011_bedrock_provider.cjs");

const conversationCollection = "ai_companion_conversations";
const uuidPattern =
  "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

const conversationValidator = structuredClone(
  bedrock.companionConversationValidator,
);
conversationValidator.$jsonSchema.required.push("context");
conversationValidator.$jsonSchema.properties.context = {
  bsonType: "object",
  additionalProperties: false,
  required: ["sources", "updatedAt"],
  properties: {
    sources: {
      bsonType: "object",
      additionalProperties: false,
      required: ["plan", "diary", "screening", "resourceIds"],
      properties: {
        plan: { bsonType: "bool" },
        diary: { bsonType: "bool" },
        screening: { bsonType: "bool" },
        resourceIds: {
          bsonType: "array",
          maxItems: 20,
          uniqueItems: true,
          items: { bsonType: "string", pattern: uuidPattern },
        },
      },
    },
    updatedAt: { bsonType: "date" },
  },
};
const contextKinds =
  conversationValidator.$jsonSchema.properties.messages.items.properties
    .contextKinds;
contextKinds.maxItems = 4;
contextKinds.items.enum.push("RESOURCE");

module.exports = {
  conversationValidator,
  async up(db) {
    await db.command({
      collMod: conversationCollection,
      validator: conversationValidator,
      validationLevel: "moderate",
      validationAction: "error",
    });
    await db
      .collection(conversationCollection)
      .updateMany({ context: { $exists: false } }, [
        {
          $set: {
            context: {
              sources: {
                plan: true,
                diary: false,
                screening: false,
                resourceIds: [],
              },
              updatedAt: "$createdAt",
            },
          },
        },
      ]);
    await db.command({
      collMod: conversationCollection,
      validator: conversationValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
  },
  async down(db) {
    const resourcesUsed = await db
      .collection(conversationCollection)
      .countDocuments({ "messages.contextKinds": "RESOURCE" });
    if (resourcesUsed > 0)
      throw new Error(
        "Cannot remove conversation context after resource provenance has been written",
      );
    await db.command({
      collMod: conversationCollection,
      validator: bedrock.companionConversationValidator,
      validationLevel: "moderate",
      validationAction: "error",
    });
    await db
      .collection(conversationCollection)
      .updateMany({}, { $unset: { context: "" } });
    await db.command({
      collMod: conversationCollection,
      validator: bedrock.companionConversationValidator,
      validationLevel: "strict",
      validationAction: "error",
    });
  },
};
