const collectionName = "companion_states";

const uuid = { bsonType: "string", pattern: "^[0-9a-fA-F-]{36}$" };

const validator = {
  $jsonSchema: {
    bsonType: "object",
    required: [
      "_id",
      "ownerAccountId",
      "revision",
      "encryptedPayload",
      "iv",
      "tag",
      "keyId",
      "updatedAt",
    ],
    additionalProperties: false,
    properties: {
      _id: uuid,
      ownerAccountId: uuid,
      revision: { bsonType: "int", minimum: 1 },
      encryptedPayload: { bsonType: "binData" },
      iv: { bsonType: "binData" },
      tag: { bsonType: "binData" },
      keyId: { bsonType: "string", minLength: 1, maxLength: 64 },
      updatedAt: { bsonType: "date" },
    },
  },
};

module.exports = {
  async up(db) {
    await db.createCollection(collectionName, {
      validator,
      validationLevel: "strict",
      validationAction: "error",
    });
    await db
      .collection(collectionName)
      .createIndex(
        { ownerAccountId: 1 },
        { name: "companion_states_owner_unique_idx", unique: true },
      );
  },

  async down(db) {
    if (await db.collection(collectionName).estimatedDocumentCount()) {
      throw new Error(
        "Cannot remove companion states after data has been written",
      );
    }
    await db.collection(collectionName).drop();
  },

  collectionName,
  validator,
};
