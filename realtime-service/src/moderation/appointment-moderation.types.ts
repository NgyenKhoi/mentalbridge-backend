import type { ObjectId } from 'mongodb';

import type { EncryptedMessageBody } from '../messages/message-encryption.service.js';

export type ReportReason =
  | 'HARASSMENT'
  | 'PRIVACY_CONCERN'
  | 'SELF_HARM_OR_CRISIS_CONCERN'
  | 'SPAM'
  | 'SEXUAL_OR_VIOLENT_CONTENT'
  | 'OTHER';

export interface StoredAppointmentReport {
  readonly _id: ObjectId;
  readonly reportId: string;
  readonly conversationId: string;
  readonly messageId: string;
  readonly reporterId: string;
  readonly reason: ReportReason;
  readonly context: EncryptedMessageBody | null;
  readonly state: 'OPEN' | 'RESOLVED';
  readonly resolution: 'NO_ACTION' | 'MESSAGE_REMOVED' | null;
  readonly resolvedBy: string | null;
  readonly resolvedAt: Date | null;
  readonly createdAt: Date;
  readonly updatedAt: Date;
  readonly schemaVersion: 1;
}

export interface StoredModerationCommand {
  readonly _id: ObjectId;
  readonly actorId: string;
  readonly idempotencyKey: string;
  readonly action: 'DELETE_MESSAGE' | 'REPORT_MESSAGE' | 'RESOLVE_REPORT';
  readonly fingerprint: string;
  readonly targetId: string;
  readonly resultId: string;
  readonly createdAt: Date;
  readonly schemaVersion: 1;
}

export interface StoredModerationAudit {
  readonly _id: ObjectId;
  readonly auditId: string;
  readonly actorId: string;
  readonly actorRole: 'USER' | 'SPECIALIST' | 'ADMIN';
  readonly action: 'DELETE_MESSAGE' | 'REPORT_MESSAGE' | 'RESOLVE_REPORT';
  readonly targetType: 'MESSAGE' | 'REPORT';
  readonly targetId: string;
  readonly reason: string;
  readonly outcome: 'SUCCEEDED';
  readonly correlationId: string;
  readonly occurredAt: Date;
  readonly schemaVersion: 1;
}
