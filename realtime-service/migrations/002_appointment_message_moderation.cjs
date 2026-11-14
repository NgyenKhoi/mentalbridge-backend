const uuid = {
  bsonType: 'string',
  pattern:
    '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
};

const encryptedBody = {
  bsonType: ['object', 'null'],
  additionalProperties: false,
  required: ['bodyCiphertext', 'bodyIv', 'bodyTag', 'keyVersion'],
  properties: {
    bodyCiphertext: { bsonType: 'string', minLength: 4, maxLength: 8192 },
    bodyIv: { bsonType: 'string', minLength: 16, maxLength: 24 },
    bodyTag: { bsonType: 'string', minLength: 24, maxLength: 24 },
    keyVersion: { bsonType: 'string', minLength: 1, maxLength: 64 },
  },
};

const reportValidator = {
  $jsonSchema: {
    bsonType: 'object',
    additionalProperties: false,
    required: [
      '_id',
      'reportId',
      'conversationId',
      'messageId',
      'reporterId',
      'reason',
      'context',
      'state',
      'resolution',
      'resolvedBy',
      'resolvedAt',
      'createdAt',
      'updatedAt',
      'schemaVersion',
    ],
    properties: {
      _id: { bsonType: 'objectId' },
      reportId: uuid,
      conversationId: uuid,
      messageId: uuid,
      reporterId: uuid,
      reason: {
        enum: [
          'HARASSMENT',
          'PRIVACY_CONCERN',
          'SELF_HARM_OR_CRISIS_CONCERN',
          'SPAM',
          'SEXUAL_OR_VIOLENT_CONTENT',
          'OTHER',
        ],
      },
      context: encryptedBody,
      state: { enum: ['OPEN', 'RESOLVED'] },
      resolution: { enum: ['NO_ACTION', 'MESSAGE_REMOVED', null] },
      resolvedBy: { bsonType: ['string', 'null'] },
      resolvedAt: { bsonType: ['date', 'null'] },
      createdAt: { bsonType: 'date' },
      updatedAt: { bsonType: 'date' },
      schemaVersion: { enum: [1] },
    },
  },
};

const commandValidator = {
  $jsonSchema: {
    bsonType: 'object',
    additionalProperties: false,
    required: [
      '_id',
      'actorId',
      'idempotencyKey',
      'action',
      'fingerprint',
      'targetId',
      'resultId',
      'resultState',
      'resultTimestamp',
      'createdAt',
      'schemaVersion',
    ],
    properties: {
      _id: { bsonType: 'objectId' },
      actorId: uuid,
      idempotencyKey: { bsonType: 'string', minLength: 16, maxLength: 128 },
      action: { enum: ['DELETE_MESSAGE', 'REPORT_MESSAGE', 'RESOLVE_REPORT'] },
      fingerprint: { bsonType: 'string', pattern: '^[0-9a-f]{64}$' },
      targetId: uuid,
      resultId: uuid,
      resultState: { enum: ['DELETED', 'OPEN', 'RESOLVED', null] },
      resultTimestamp: { bsonType: ['date', 'null'] },
      createdAt: { bsonType: 'date' },
      schemaVersion: { enum: [1] },
    },
  },
};

const auditValidator = {
  $jsonSchema: {
    bsonType: 'object',
    additionalProperties: false,
    required: [
      '_id',
      'auditId',
      'actorId',
      'actorRole',
      'action',
      'targetType',
      'targetId',
      'reason',
      'outcome',
      'correlationId',
      'occurredAt',
      'schemaVersion',
    ],
    properties: {
      _id: { bsonType: 'objectId' },
      auditId: uuid,
      actorId: uuid,
      actorRole: { enum: ['USER', 'SPECIALIST', 'ADMIN'] },
      action: { enum: ['DELETE_MESSAGE', 'REPORT_MESSAGE', 'RESOLVE_REPORT'] },
      targetType: { enum: ['MESSAGE', 'REPORT'] },
      targetId: uuid,
      reason: { bsonType: 'string', minLength: 1, maxLength: 500 },
      outcome: { enum: ['SUCCEEDED'] },
      correlationId: { bsonType: 'string', minLength: 1, maxLength: 128 },
      occurredAt: { bsonType: 'date' },
      schemaVersion: { enum: [1] },
    },
  },
};

async function ensureCollection(db, name, validator) {
  const exists = await db.listCollections({ name }, { nameOnly: true }).hasNext();
  if (exists)
    await db.command({
      collMod: name,
      validator,
      validationLevel: 'strict',
      validationAction: 'error',
    });
  else
    await db.createCollection(name, {
      validator,
      validationLevel: 'strict',
      validationAction: 'error',
    });
}

module.exports = {
  async up(db) {
    await ensureCollection(db, 'appointment_message_reports', reportValidator);
    await ensureCollection(db, 'appointment_moderation_commands', commandValidator);
    await ensureCollection(db, 'appointment_moderation_audit', auditValidator);
    await db
      .collection('appointment_message_reports')
      .createIndex({ reportId: 1 }, { name: 'appointment_reports_id_unique_idx', unique: true });
    await db
      .collection('appointment_message_reports')
      .createIndex(
        { reporterId: 1, messageId: 1 },
        { name: 'appointment_reports_reporter_message_unique_idx', unique: true },
      );
    await db
      .collection('appointment_message_reports')
      .createIndex(
        { state: 1, createdAt: -1, _id: -1 },
        { name: 'appointment_reports_review_idx' },
      );
    await db
      .collection('appointment_moderation_commands')
      .createIndex(
        { actorId: 1, idempotencyKey: 1 },
        { name: 'appointment_commands_actor_key_unique_idx', unique: true },
      );
    await db
      .collection('appointment_moderation_audit')
      .createIndex(
        { targetType: 1, targetId: 1, occurredAt: -1 },
        { name: 'appointment_audit_target_idx' },
      );
  },
  async down(db) {
    await db.collection('appointment_moderation_audit').drop();
    await db.collection('appointment_moderation_commands').drop();
    await db.collection('appointment_message_reports').drop();
  },
  validators: { reports: reportValidator, commands: commandValidator, audit: auditValidator },
};
