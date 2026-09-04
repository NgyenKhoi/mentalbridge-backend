import { Injectable } from '@nestjs/common';
import { MongoServerError, ObjectId } from 'mongodb';

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
