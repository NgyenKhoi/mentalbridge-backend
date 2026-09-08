import type { ObjectId } from 'mongodb';

export interface StoredMessage {
  readonly _id: ObjectId;
  readonly messageId: string;
  readonly conversationId: string;
  readonly senderId: string;
  readonly clientMessageId: string;
  readonly type: 'TEXT';
  readonly bodyCiphertext: string;
  readonly bodyIv: string;
  readonly bodyTag: string;
  readonly keyVersion: string;
  readonly commandFingerprint: string;
  readonly sentAt: Date;
  readonly editedAt: Date | null;
  readonly deletedAt: Date | null;
  readonly moderationHold: boolean;
  readonly schemaVersion: 1;
}

export interface MessageView {
  readonly messageId: string;
  readonly conversationId: string;
  readonly senderId: string;
  readonly clientMessageId: string;
  readonly type: 'TEXT';
  readonly content: string;
  readonly sentAt: string;
  readonly schemaVersion: 1;
}
