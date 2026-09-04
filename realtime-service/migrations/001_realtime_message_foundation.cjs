const uuid = {
  bsonType: 'string',
  pattern:
    '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
};

const conversationValidator = {
  $jsonSchema: {
    bsonType: 'object',
    additionalProperties: false,
    required: [
      '_id',
      'conversationId',
      'appointmentId',
      'participants',
      'status',
      'lastMessageAt',
      'closedAt',
      'schemaVersion',
      'createdAt',
      'updatedAt',
    ],
    properties: {
      _id: { bsonType: 'objectId' },
      conversationId: uuid,
      appointmentId: uuid,
      participants: {
        bsonType: 'array',
        minItems: 2,
        maxItems: 2,
        items: {
          bsonType: 'object',
          additionalProperties: false,
          required: ['accountId', 'role', 'joinedAt'],
          properties: {
            accountId: uuid,
            role: { enum: ['USER', 'SPECIALIST'] },
            joinedAt: { bsonType: 'date' },
          },
        },
      },
      status: { enum: ['ACTIVE', 'CLOSED'] },
      lastMessageAt: { bsonType: ['date', 'null'] },
      closedAt: { bsonType: ['date', 'null'] },
      schemaVersion: { enum: [1] },
      createdAt: { bsonType: 'date' },
      updatedAt: { bsonType: 'date' },
    },
  },
};

const messageValidator = {
  $jsonSchema: {
    bsonType: 'object',
    additionalProperties: false,
    required: [
      '_id',
      'messageId',
      'conversationId',
      'senderId',
      'clientMessageId',
      'type',
      'bodyCiphertext',
      'bodyIv',
      'bodyTag',
      'keyVersion',
      'commandFingerprint',
      'sentAt',
      'editedAt',
      'deletedAt',
      'moderationHold',
      'schemaVersion',
    ],
    properties: {
      _id: { bsonType: 'objectId' },
      messageId: uuid,
      conversationId: uuid,
      senderId: uuid,
      clientMessageId: uuid,
      type: { enum: ['TEXT'] },
      bodyCiphertext: { bsonType: 'string', minLength: 4, maxLength: 32768 },
      bodyIv: { bsonType: 'string', minLength: 16, maxLength: 24 },
      bodyTag: { bsonType: 'string', minLength: 24, maxLength: 24 },
      keyVersion: { bsonType: 'string', minLength: 1, maxLength: 64 },
      commandFingerprint: { bsonType: 'string', minLength: 43, maxLength: 64 },
      sentAt: { bsonType: 'date' },
      editedAt: { bsonType: ['date', 'null'] },
      deletedAt: { bsonType: ['date', 'null'] },
      moderationHold: { bsonType: 'bool' },
      schemaVersion: { enum: [1] },
    },
  },
};

async function ensureCollection(db, name, validator) {
  const exists = await db.listCollections({ name }, { nameOnly: true }).hasNext();
  if (exists) {
    await db.command({
      collMod: name,
      validator,
      validationLevel: 'strict',
      validationAction: 'error',
    });
  } else {
    await db.createCollection(name, {
      validator,
      validationLevel: 'strict',
      validationAction: 'error',
    });
  }
}

module.exports = {
  async up(db) {
    await ensureCollection(db, 'conversations', conversationValidator);
    await ensureCollection(db, 'messages', messageValidator);

    await db
      .collection('conversations')
      .createIndex({ conversationId: 1 }, { name: 'conversations_id_unique_idx', unique: true });
    await db
      .collection('conversations')
      .createIndex(
        { appointmentId: 1 },
        { name: 'conversations_appointment_unique_idx', unique: true },
      );
    await db
      .collection('conversations')
      .createIndex(
        { 'participants.accountId': 1, lastMessageAt: -1 },
        { name: 'conversations_participant_activity_idx' },
      );

    await db
      .collection('messages')
      .createIndex({ messageId: 1 }, { name: 'messages_id_unique_idx', unique: true });
    await db
      .collection('messages')
      .createIndex(
        { conversationId: 1, sentAt: -1, _id: -1 },
        { name: 'messages_conversation_cursor_idx' },
      );
    await db
      .collection('messages')
      .createIndex(
        { senderId: 1, clientMessageId: 1 },
        { name: 'messages_sender_client_unique_idx', unique: true },
      );
  },

  async down(db) {
    await db.collection('messages').drop();
    await db.collection('conversations').drop();
  },

  validators: { conversations: conversationValidator, messages: messageValidator },
};
