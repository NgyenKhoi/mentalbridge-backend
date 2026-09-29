import { HttpStatus, Injectable } from '@nestjs/common';
import { MongoServerError, ObjectId } from 'mongodb';

import type { ConversationEligibilityDecision } from './conversation-eligibility.js';
import { MongoDatabaseService } from '../database/mongo-database.service.js';
import { ApplicationException } from '../http/application.exception.js';

interface StoredConversation {
  readonly _id: ObjectId;
  readonly conversationId: string;
  readonly appointmentId: string;
  readonly participants: readonly {
    readonly accountId: string;
    readonly role: 'USER' | 'SPECIALIST';
    readonly joinedAt: Date;
  }[];
  readonly status: 'ACTIVE' | 'CLOSED';
  readonly lastMessageAt: Date | null;
  readonly closedAt: Date | null;
  readonly schemaVersion: 1;
  readonly createdAt: Date;
  readonly updatedAt: Date;
}

@Injectable()
export class ConversationRepository {
  constructor(private readonly database: MongoDatabaseService) {}

  async bind(decision: ConversationEligibilityDecision): Promise<void> {
    const collection = this.database.collection<StoredConversation>('conversations');
    const existing = await collection.findOne({ conversationId: decision.conversationId });
    if (existing) {
      this.assertBinding(existing, decision);
      if (
        existing.status === 'CLOSED' &&
        (decision.phase === 'WAITING' || decision.phase === 'ACTIVE')
      ) {
        throw new ApplicationException(
          HttpStatus.FORBIDDEN,
          'ACCESS_DENIED',
          'Conversation is closed',
        );
      }
      if (
        decision.phase === 'ENDED' ||
        decision.phase === 'CANCELLED' ||
        decision.phase === 'RESCHEDULED'
      ) {
        const closedAt = new Date(
          decision.phase === 'ENDED' ? decision.scheduledEndAt : decision.serverTime,
        );
        await collection.updateOne(
          { conversationId: decision.conversationId, status: 'ACTIVE' },
          { $set: { status: 'CLOSED', closedAt, updatedAt: new Date(decision.serverTime) } },
        );
      }
      return;
    }
    if (decision.phase !== 'WAITING' && decision.phase !== 'ACTIVE') return;
    const now = new Date(decision.serverTime);
    const conversation: StoredConversation = {
      _id: new ObjectId(),
      conversationId: decision.conversationId,
      appointmentId: decision.appointmentId,
      participants: [
        { accountId: decision.userAccountId, role: 'USER', joinedAt: now },
        { accountId: decision.specialistAccountId, role: 'SPECIALIST', joinedAt: now },
      ],
      status: 'ACTIVE',
      lastMessageAt: null,
      closedAt: null,
      schemaVersion: 1,
      createdAt: now,
      updatedAt: now,
    };
    try {
      await collection.insertOne(conversation);
    } catch (error) {
      if (!(error instanceof MongoServerError) || error.code !== 11000) throw error;
      const raced = await collection.findOne({ conversationId: decision.conversationId });
      if (!raced) throw error;
      this.assertBinding(raced, decision);
    }
  }

  async recordMessage(conversationId: string, sentAt: Date): Promise<void> {
    const result = await this.database
      .collection<StoredConversation>('conversations')
      .updateOne(
        { conversationId, status: 'ACTIVE' },
        { $set: { lastMessageAt: sentAt, updatedAt: sentAt } },
      );
    if (result.matchedCount !== 1) {
      throw new ApplicationException(
        HttpStatus.FORBIDDEN,
        'ACCESS_DENIED',
        'Conversation access is denied',
      );
    }
  }

  private assertBinding(
    conversation: StoredConversation,
    decision: ConversationEligibilityDecision,
  ): void {
    const participants = new Map(
      conversation.participants.map((participant) => [participant.role, participant.accountId]),
    );
    if (
      conversation.appointmentId !== decision.appointmentId ||
      participants.get('USER') !== decision.userAccountId ||
      participants.get('SPECIALIST') !== decision.specialistAccountId
    ) {
      throw new ApplicationException(
        HttpStatus.CONFLICT,
        'CONVERSATION_BINDING_CONFLICT',
        'Conversation appointment binding conflicts with authoritative eligibility',
      );
    }
  }
}
