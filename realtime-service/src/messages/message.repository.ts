import { Injectable } from '@nestjs/common';
import { type ClientSession, MongoServerError, ObjectId } from 'mongodb';

import { MongoDatabaseService } from '../database/mongo-database.service.js';
import type { StoredMessage } from './message.types.js';

export interface InsertMessageResult {
  readonly message: StoredMessage;
  readonly duplicate: boolean;
}

export interface MessageCursor {
  readonly sentAt: Date;
  readonly id: ObjectId;
}

@Injectable()
export class MessageRepository {
  constructor(private readonly database: MongoDatabaseService) {}

  async insert(message: StoredMessage): Promise<InsertMessageResult> {
    try {
      await this.database.collection<StoredMessage>('messages').insertOne(message);
      return { message, duplicate: false };
    } catch (error) {
      if (!(error instanceof MongoServerError) || error.code !== 11000) throw error;
      const existing = await this.database.collection<StoredMessage>('messages').findOne({
        senderId: message.senderId,
        clientMessageId: message.clientMessageId,
      });
      if (!existing) throw error;
      return { message: existing, duplicate: true };
    }
  }

  async removeNew(message: StoredMessage): Promise<void> {
    await this.database.collection<StoredMessage>('messages').deleteOne({
      _id: message._id,
      messageId: message.messageId,
    });
  }

  find(conversationId: string, messageId: string): Promise<StoredMessage | null> {
    return this.database
      .collection<StoredMessage>('messages')
      .findOne({ conversationId, messageId });
  }

  findInSession(
    conversationId: string,
    messageId: string,
    session: ClientSession,
  ): Promise<StoredMessage | null> {
    return this.database
      .collection<StoredMessage>('messages')
      .findOne({ conversationId, messageId }, { session });
  }

  async tombstone(
    conversationId: string,
    messageId: string,
    senderId: string | undefined,
    deletedAt: Date,
    session?: ClientSession,
  ): Promise<StoredMessage | null> {
    const result = await this.database.collection<StoredMessage>('messages').findOneAndUpdate(
      {
        conversationId,
        messageId,
        ...(senderId ? { senderId } : {}),
        deletedAt: null,
      },
      { $set: { deletedAt } },
      { returnDocument: 'after', ...(session ? { session } : {}) },
    );
    if (result) return result;
    return session
      ? this.findInSession(conversationId, messageId, session)
      : this.find(conversationId, messageId);
  }

  async holdForModeration(
    conversationId: string,
    messageId: string,
    session?: ClientSession,
  ): Promise<boolean> {
    const result = await this.database
      .collection<StoredMessage>('messages')
      .updateOne(
        { conversationId, messageId, deletedAt: null },
        { $set: { moderationHold: true } },
        session ? { session } : {},
      );
    return result.matchedCount === 1;
  }

  async list(
    conversationId: string,
    limit: number,
    cursor?: MessageCursor,
  ): Promise<{ rows: StoredMessage[]; hasMore: boolean }> {
    const cursorFilter = cursor
      ? {
          $or: [
            { sentAt: { $lt: cursor.sentAt } },
            { sentAt: cursor.sentAt, _id: { $lt: cursor.id } },
          ],
        }
      : {};
    const rows = await this.database
      .collection<StoredMessage>('messages')
      .find({ conversationId, ...cursorFilter })
      .sort({ sentAt: -1, _id: -1 })
      .limit(limit + 1)
      .toArray();
    return { rows: rows.slice(0, limit), hasMore: rows.length > limit };
  }
}
