const uuid = {
  bsonType: "string",
  pattern:
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
};
const date = { bsonType: "date" };
const number = (max) => ({
  bsonType: ["int", "long"],
  minimum: 1,
  maximum: max,
});
const closed = (properties) => ({
  bsonType: "object",
  additionalProperties: false,
  required: Object.keys(properties),
  properties,
});
const validator = {
  $jsonSchema: closed({
    _id: uuid,
    configHash: { bsonType: "string", pattern: "^[A-Za-z0-9_-]{43}$" },
    startsAt: date,
    endsAt: date,
    caps: closed({
      sessions: number(2),
      concurrent: { enum: [1] },
      turns: number(12),
      voiceSeconds: number(480),
      ttsCharacters: number(4000),
    }),
    allocation: closed({
      turns: number(12),
      voiceSeconds: number(480),
      ttsCharacters: number(4000),
    }),
    reservations: {
      bsonType: "array",
      maxItems: 2,
      items: closed({
        voiceSessionId: uuid,
        createdAt: date,
        releasedAt: { bsonType: ["date", "null"] },
        workerClaimed: { bsonType: "bool" },
        turns: number(12),
        voiceSeconds: number(480),
        ttsCharacters: number(4000),
        turnReservations: {
          bsonType: "array",
          maxItems: 12,
          items: closed({ turnId: uuid, sequence: number(12) }),
        },
        speechReservations: {
          bsonType: "array",
          maxItems: 12,
          items: closed({
            turnId: uuid,
            replyId: uuid,
            characters: number(1000),
          }),
        },
      }),
    },
    createdAt: date,
    updatedAt: date,
    expiresAt: date,
  }),
};
const collectionName = "ai_voice_measurement_campaigns";
module.exports = {
  collectionName,
  validator,
  async up(db) {
    if (
      await db
        .listCollections({ name: collectionName }, { nameOnly: true })
        .hasNext()
    )
      await db.command({
        collMod: collectionName,
        validator,
        validationLevel: "strict",
        validationAction: "error",
      });
    else
      await db.createCollection(collectionName, {
        validator,
        validationLevel: "strict",
        validationAction: "error",
      });
    await db.collection(collectionName).createIndex(
      { "reservations.voiceSessionId": 1 },
      {
        unique: true,
        name: "uq_measurement_session",
        partialFilterExpression: {
          "reservations.voiceSessionId": { $exists: true },
        },
      },
    );
    await db
      .collection(collectionName)
      .createIndex(
        { expiresAt: 1 },
        { name: "ttl_voice_measurement_campaign", expireAfterSeconds: 0 },
      );
  },
  async down(db) {
    if (await db.collection(collectionName).countDocuments())
      throw new Error(
        "Retain spent campaign reservations until normal metadata expiry",
      );
    await db.collection(collectionName).drop();
  },
};
