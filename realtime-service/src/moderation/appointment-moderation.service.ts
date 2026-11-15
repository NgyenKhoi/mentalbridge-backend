import { createHash, randomUUID } from 'node:crypto';

import { HttpStatus, Inject, Injectable } from '@nestjs/common';
import { type ClientSession, MongoServerError, ObjectId } from 'mongodb';

import type { ConversationEligibility } from '../conversations/conversation-eligibility.js';
import { ConversationRepository } from '../conversations/conversation.repository.js';
import { ApplicationException } from '../http/application.exception.js';
import { MessageEncryptionService } from '../messages/message-encryption.service.js';
import { MessageRepository } from '../messages/message.repository.js';
import type { AuthenticatedPrincipal } from '../security/principal.js';
import { ELIGIBILITY_TOKEN } from '../shared/tokens.js';
import { AppointmentModerationRepository } from './appointment-moderation.repository.js';
import type {
  ReportReason,
  StoredAppointmentReport,
  StoredModerationAudit,
  StoredModerationCommand,
} from './appointment-moderation.types.js';

interface CommandCompletion<T> {
  readonly value: T;
  readonly resultId: string;
  readonly resultState: NonNullable<StoredModerationCommand['resultState']>;
  readonly resultTimestamp: Date | null;
}

type CommandExecution<T> =
  | { readonly duplicate: false; readonly value: T }
  | { readonly duplicate: true; readonly command: StoredModerationCommand };

@Injectable()
export class AppointmentModerationService {
  constructor(
    private readonly repository: AppointmentModerationRepository,
    private readonly messages: MessageRepository,
    private readonly conversations: ConversationRepository,
    private readonly encryption: MessageEncryptionService,
    @Inject(ELIGIBILITY_TOKEN) private readonly eligibility: ConversationEligibility,
  ) {}

  async removeOwn(
    bearerToken: string,
    principal: AuthenticatedPrincipal,
    conversationId: string,
    messageId: string,
    idempotencyKey: string,
    correlationId: string,
  ) {
    const decision = await this.eligibility.check(
      bearerToken,
      principal.accountId,
      conversationId,
      'delete',
      correlationId,
    );
    await this.conversations.bind(decision);
    const fingerprint = this.fingerprint({ conversationId, messageId });
    const requestedAt = new Date();
    const command = this.command(
      principal.accountId,
      idempotencyKey,
      'DELETE_MESSAGE',
      fingerprint,
      messageId,
      messageId,
      requestedAt,
    );
    const execution = await this.runCommand(command, async (session) => {
      const existing = await this.messages.findInSession(conversationId, messageId, session);
      if (existing?.senderId !== principal.accountId) throw notFound();
      if (existing.deletedAt)
        return {
          value: this.deleteReceipt(messageId, existing.deletedAt, true),
          resultId: messageId,
          resultState: 'DELETED' as const,
          resultTimestamp: existing.deletedAt,
        };
      const message = await this.messages.tombstone(
        conversationId,
        messageId,
        principal.accountId,
        requestedAt,
        session,
      );
      if (message?.senderId !== principal.accountId || !message.deletedAt) throw notFound();
      await this.audit(
        principal,
        'DELETE_MESSAGE',
        'MESSAGE',
        messageId,
        'OWNER_REQUEST',
        correlationId,
        message.deletedAt,
        session,
      );
      return {
        value: this.deleteReceipt(messageId, message.deletedAt, false),
        resultId: messageId,
        resultState: 'DELETED' as const,
        resultTimestamp: message.deletedAt,
      };
    });
    if (!execution.duplicate) return execution.value;
    if (!execution.command.resultTimestamp)
      throw new Error('Delete command is missing its authoritative timestamp');
    return this.deleteReceipt(execution.command.resultId, execution.command.resultTimestamp, true);
  }

  async report(
    bearerToken: string,
    principal: AuthenticatedPrincipal,
    conversationId: string,
    messageId: string,
    idempotencyKey: string,
    reason: ReportReason,
    context: string | undefined,
    correlationId: string,
  ) {
    const trimmedContext = context?.trim();
    const normalizedContext = trimmedContext?.length ? trimmedContext : undefined;
    const decision = await this.eligibility.check(
      bearerToken,
      principal.accountId,
      conversationId,
      'report',
      correlationId,
    );
    await this.conversations.bind(decision);
    const fingerprint = this.fingerprint({
      conversationId,
      messageId,
      reason,
      context: normalizedContext,
    });
    const now = new Date();
    const report: StoredAppointmentReport = {
      _id: new ObjectId(),
      reportId: randomUUID(),
      conversationId,
      messageId,
      reporterId: principal.accountId,
      reason,
      context: normalizedContext ? this.encryption.encrypt(normalizedContext) : null,
      state: 'OPEN',
      resolution: null,
      resolvedBy: null,
      resolvedAt: null,
      createdAt: now,
      updatedAt: now,
      schemaVersion: 1,
    };
    const command = this.command(
      principal.accountId,
      idempotencyKey,
      'REPORT_MESSAGE',
      fingerprint,
      messageId,
      report.reportId,
      now,
    );
    const execution = await this.runCommand(command, async (session) => {
      const target = await this.messages.findInSession(conversationId, messageId, session);
      if (!target || target.deletedAt) throw notFound();
      const prior = await this.repository.findReporterTarget(
        principal.accountId,
        messageId,
        session,
      );
      if (prior)
        return {
          value: this.reportReceipt(prior.reportId, prior.state, true),
          resultId: prior.reportId,
          resultState: prior.state,
          resultTimestamp: prior.updatedAt,
        };
      if (!(await this.messages.holdForModeration(conversationId, messageId, session)))
        throw notFound();
      await this.repository.insertReport(report, session);
      await this.audit(
        principal,
        'REPORT_MESSAGE',
        'MESSAGE',
        messageId,
        reason,
        correlationId,
        now,
        session,
      );
      return {
        value: this.reportReceipt(report.reportId, report.state, false),
        resultId: report.reportId,
        resultState: report.state,
        resultTimestamp: report.updatedAt,
      };
    });
    if (!execution.duplicate) return execution.value;
    const persisted = await this.repository.findReport(execution.command.resultId);
    if (!persisted) throw new Error('Report command result is unavailable');
    return this.reportReceipt(persisted.reportId, persisted.state, true);
  }

  async list(
    principal: AuthenticatedPrincipal,
    state: 'OPEN' | 'RESOLVED' | undefined,
    limit: number,
  ) {
    this.assertAdmin(principal);
    const rows = await this.repository.listReports(state, limit);
    return { items: rows.map((row) => this.reportSummary(row)), count: rows.length };
  }

  async detail(principal: AuthenticatedPrincipal, reportId: string) {
    this.assertAdmin(principal);
    const report = await this.repository.findReport(reportId);
    if (!report) throw notFound();
    const message = await this.messages.find(report.conversationId, report.messageId);
    return {
      ...this.reportSummary(report),
      context: report.context ? this.encryption.decrypt(report.context) : null,
      message: message
        ? {
            messageId: message.messageId,
            senderId: message.senderId,
            content: this.encryption.decrypt(message),
            sentAt: message.sentAt.toISOString(),
            state: message.deletedAt ? ('DELETED' as const) : ('VISIBLE' as const),
            deletedAt: message.deletedAt?.toISOString() ?? null,
          }
        : null,
    };
  }

  async resolve(
    principal: AuthenticatedPrincipal,
    reportId: string,
    idempotencyKey: string,
    resolution: 'NO_ACTION' | 'MESSAGE_REMOVED',
    reason: string,
    correlationId: string,
  ) {
    this.assertAdmin(principal);
    const fingerprint = this.fingerprint({ reportId, resolution, reason });
    const now = new Date();
    const command = this.command(
      principal.accountId,
      idempotencyKey,
      'RESOLVE_REPORT',
      fingerprint,
      reportId,
      reportId,
      now,
    );
    const execution = await this.runCommand(command, async (session) => {
      const report = await this.repository.findReport(reportId, session);
      if (!report) throw notFound();
      if (report.state === 'RESOLVED') {
        if (report.resolution !== resolution) throw conflict('REPORT_ALREADY_RESOLVED');
        return {
          value: { reportId, state: 'RESOLVED' as const, resolution, duplicate: true },
          resultId: reportId,
          resultState: 'RESOLVED' as const,
          resultTimestamp: report.resolvedAt,
        };
      }
      const resolved = await this.repository.resolve(
        reportId,
        principal.accountId,
        resolution,
        now,
        session,
      );
      if (resolved?.resolution !== resolution) throw conflict('REPORT_ALREADY_RESOLVED');
      if (resolution === 'MESSAGE_REMOVED') {
        const message = await this.messages.tombstone(
          report.conversationId,
          report.messageId,
          undefined,
          now,
          session,
        );
        if (!message) throw notFound();
      }
      await this.audit(
        principal,
        'RESOLVE_REPORT',
        'REPORT',
        reportId,
        reason,
        correlationId,
        now,
        session,
      );
      return {
        value: { reportId, state: 'RESOLVED' as const, resolution, duplicate: false },
        resultId: reportId,
        resultState: 'RESOLVED' as const,
        resultTimestamp: now,
      };
    });
    if (!execution.duplicate) return execution.value;
    return { reportId, state: 'RESOLVED' as const, resolution, duplicate: true };
  }

  private command(
    actorId: string,
    key: string,
    action: StoredModerationCommand['action'],
    fingerprint: string,
    targetId: string,
    resultId: string,
    createdAt: Date,
  ): StoredModerationCommand {
    return {
      _id: new ObjectId(),
      actorId,
      idempotencyKey: key,
      action,
      fingerprint,
      targetId,
      resultId,
      resultState: null,
      resultTimestamp: null,
      createdAt,
      schemaVersion: 1,
    };
  }

  private async audit(
    principal: AuthenticatedPrincipal,
    action: StoredModerationAudit['action'],
    targetType: StoredModerationAudit['targetType'],
    targetId: string,
    reason: string,
    correlationId: string,
    occurredAt: Date,
    session: ClientSession,
  ) {
    await this.repository.insertAudit(
      {
        _id: new ObjectId(),
        auditId: randomUUID(),
        actorId: principal.accountId,
        actorRole: principal.role,
        action,
        targetType,
        targetId,
        reason,
        outcome: 'SUCCEEDED',
        correlationId,
        occurredAt,
        schemaVersion: 1,
      },
      session,
    );
  }

  private async runCommand<T>(
    command: StoredModerationCommand,
    work: (session: ClientSession) => Promise<CommandCompletion<T>>,
  ): Promise<CommandExecution<T>> {
    let lastCollision: MongoServerError | undefined;
    for (let attempt = 0; attempt < 3; attempt += 1) {
      try {
        const value = await this.repository.withTransaction(async (session) => {
          await this.repository.insertCommand(command, session);
          const completion = await work(session);
          await this.repository.completeCommand(
            command._id,
            completion.resultId,
            completion.resultState,
            completion.resultTimestamp,
            session,
          );
          return completion.value;
        });
        return { duplicate: false, value };
      } catch (error) {
        if (!(error instanceof MongoServerError) || error.code !== 11000) throw error;
        lastCollision = error;
        const duplicate = await this.repository.findCommand(
          command.actorId,
          command.idempotencyKey,
        );
        if (!duplicate) continue;
        if (duplicate.action !== command.action || duplicate.fingerprint !== command.fingerprint)
          throw conflict('IDEMPOTENCY_CONFLICT');
        if (!duplicate.resultState)
          throw new Error('Moderation command committed without an authoritative result', {
            cause: error,
          });
        return { duplicate: true, command: duplicate };
      }
    }
    throw new Error('Moderation command collision could not be reconciled', {
      cause: lastCollision,
    });
  }

  private deleteReceipt(messageId: string, deletedAt: Date, duplicate: boolean) {
    return { messageId, state: 'DELETED' as const, deletedAt: deletedAt.toISOString(), duplicate };
  }

  private reportReceipt(
    reportId: string,
    state: StoredAppointmentReport['state'],
    duplicate: boolean,
  ) {
    return { reportId, state, duplicate };
  }

  private reportSummary(report: StoredAppointmentReport) {
    return {
      reportId: report.reportId,
      conversationId: report.conversationId,
      messageId: report.messageId,
      reason: report.reason,
      state: report.state,
      resolution: report.resolution,
      createdAt: report.createdAt.toISOString(),
      updatedAt: report.updatedAt.toISOString(),
    };
  }

  private assertAdmin(principal: AuthenticatedPrincipal) {
    if (principal.role !== 'ADMIN')
      throw new ApplicationException(
        HttpStatus.FORBIDDEN,
        'ACCESS_DENIED',
        'Administrator authority is required',
      );
  }

  private fingerprint(value: unknown) {
    return createHash('sha256').update(JSON.stringify(value)).digest('hex');
  }
}

function notFound() {
  return new ApplicationException(
    HttpStatus.NOT_FOUND,
    'MODERATION_TARGET_NOT_FOUND',
    'The moderation target was not found',
  );
}

function conflict(code: string) {
  return new ApplicationException(
    HttpStatus.CONFLICT,
    code,
    'The moderation command conflicts with existing state',
  );
}
