import { randomUUID } from 'node:crypto';

import { HttpStatus, Inject, Injectable } from '@nestjs/common';
import { ObjectId } from 'mongodb';
import { z } from 'zod';

import type { ConversationEligibility } from '../conversations/conversation-eligibility.js';
import { ApplicationException } from '../http/application.exception.js';
import { ELIGIBILITY_TOKEN, METRICS_TOKEN } from '../shared/tokens.js';
import type { RealtimeMetrics } from '../observability/metrics.js';
import { MessageEncryptionService } from './message-encryption.service.js';
import { MessageRepository, type MessageCursor } from './message.repository.js';
import type { MessageView, StoredMessage } from './message.types.js';

const cursorSchema = z
  .object({ sentAt: z.iso.datetime(), id: z.string().regex(/^[0-9a-f]{24}$/) })
  .strict();

export interface SendMessageInput {
  readonly conversationId: string;
  readonly senderId: string;
  readonly clientMessageId: string;
  readonly type: 'TEXT';
  readonly content: string;
}

@Injectable()
export class MessageService {
  constructor(
    private readonly repository: MessageRepository,
    private readonly encryption: MessageEncryptionService,
    @Inject(ELIGIBILITY_TOKEN) private readonly eligibility: ConversationEligibility,
    @Inject(METRICS_TOKEN) private readonly metrics: RealtimeMetrics,
  ) {}

  async subscribe(accountId: string, conversationId: string): Promise<void> {
    await this.eligibility.assertEligible(accountId, conversationId, 'subscribe');
  }

  async send(input: SendMessageInput): Promise<{ message: MessageView; duplicate: boolean }> {
    await this.eligibility.assertEligible(input.senderId, input.conversationId, 'send');
    const fingerprint = this.encryption.fingerprint(
      input.conversationId,
      input.type,
      input.content,
    );
    const encrypted = this.encryption.encrypt(input.content);
    const stored: StoredMessage = {
      _id: new ObjectId(),
      messageId: randomUUID(),
      conversationId: input.conversationId,
      senderId: input.senderId,
      clientMessageId: input.clientMessageId,
      type: input.type,
      ...encrypted,
      commandFingerprint: fingerprint,
      sentAt: new Date(),
      editedAt: null,
      deletedAt: null,
      moderationHold: false,
      schemaVersion: 1,
    };
    const stopTimer = this.metrics.messagePersistSeconds.startTimer();
    try {
      const result = await this.repository.insert(stored);
      const expectedFingerprint = result.duplicate
        ? this.encryption.fingerprint(
            input.conversationId,
            input.type,
            input.content,
            result.message.keyVersion,
          )
        : fingerprint;
      if (result.duplicate && result.message.commandFingerprint !== expectedFingerprint) {
        throw new ApplicationException(
          HttpStatus.CONFLICT,
          'IDEMPOTENCY_CONFLICT',
          'Client message ID was used for different content',
        );
      }
      return { message: this.toView(result.message), duplicate: result.duplicate };
    } finally {
      stopTimer();
    }
  }

  async history(
    accountId: string,
    conversationId: string,
    limit: number,
    encodedCursor?: string,
  ): Promise<{ items: MessageView[]; nextCursor: string | null; hasMore: boolean }> {
    await this.eligibility.assertEligible(accountId, conversationId, 'history');
    const cursor = encodedCursor ? this.decodeCursor(encodedCursor) : undefined;
    const page = await this.repository.list(conversationId, limit, cursor);
    const last = page.rows.at(-1);
    return {
      items: page.rows.map((message) => this.toView(message)),
      nextCursor: page.hasMore && last ? this.encodeCursor(last) : null,
      hasMore: page.hasMore,
    };
  }

  private toView(message: StoredMessage): MessageView {
    return {
      messageId: message.messageId,
      conversationId: message.conversationId,
      senderId: message.senderId,
      clientMessageId: message.clientMessageId,
      type: message.type,
      content: this.encryption.decrypt(message),
      sentAt: message.sentAt.toISOString(),
      schemaVersion: 1,
    };
  }

  private encodeCursor(message: StoredMessage): string {
    return Buffer.from(
      JSON.stringify({ sentAt: message.sentAt.toISOString(), id: message._id.toHexString() }),
    ).toString('base64url');
  }

  private decodeCursor(encoded: string): MessageCursor {
    try {
      const parsed = cursorSchema.parse(
        JSON.parse(Buffer.from(encoded, 'base64url').toString('utf8')),
      );
      return { sentAt: new Date(parsed.sentAt), id: new ObjectId(parsed.id) };
    } catch {
      throw new ApplicationException(
        HttpStatus.BAD_REQUEST,
        'INVALID_CURSOR',
        'History cursor is invalid',
      );
    }
  }
}
