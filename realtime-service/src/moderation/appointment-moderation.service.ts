import { createHash, randomUUID } from 'node:crypto';

import { HttpStatus, Inject, Injectable } from '@nestjs/common';
import { ObjectId } from 'mongodb';

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
    const duplicate = await this.replay(
      principal.accountId,
      idempotencyKey,
      'DELETE_MESSAGE',
      fingerprint,
    );
    if (duplicate)
      return {
        messageId,
        state: 'DELETED' as const,
        deletedAt: duplicate.createdAt.toISOString(),
        duplicate: true,
      };
    const existing = await this.messages.find(conversationId, messageId);
    if (existing?.senderId !== principal.accountId) throw notFound();
    const now = existing.deletedAt ?? new Date();
    const message = existing.deletedAt
      ? existing
      : await this.messages.tombstone(conversationId, messageId, principal.accountId, now);
    if (message?.senderId !== principal.accountId) throw notFound();
    const command = this.command(
      principal.accountId,
      idempotencyKey,
      'DELETE_MESSAGE',
      fingerprint,
      messageId,
      messageId,
      now,
    );
    await this.repository.insertCommand(command);
    await this.audit(
      principal,
      'DELETE_MESSAGE',
      'MESSAGE',
      messageId,
      'OWNER_REQUEST',
      correlationId,
      now,
    );
    return {
      messageId,
      state: 'DELETED' as const,
      deletedAt: now.toISOString(),
      duplicate: existing.deletedAt !== null,
    };
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
    const duplicate = await this.replay(
      principal.accountId,
      idempotencyKey,
      'REPORT_MESSAGE',
      fingerprint,
    );
    if (duplicate) return this.reportReceipt(duplicate.resultId, true);
    const target = await this.messages.find(conversationId, messageId);
    if (!target || target.deletedAt) throw notFound();
    const prior = await this.repository.findReporterTarget(principal.accountId, messageId);
    if (prior) {
      await this.repository.insertCommand(
        this.command(
          principal.accountId,
          idempotencyKey,
          'REPORT_MESSAGE',
          fingerprint,
          messageId,
          prior.reportId,
          new Date(),
        ),
      );
      return this.reportReceipt(prior.reportId, true);
    }
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
    if (!(await this.messages.holdForModeration(conversationId, messageId))) throw notFound();
    const inserted = await this.repository.insertReport(report);
    const persisted = inserted
      ? report
      : await this.repository.findReporterTarget(principal.accountId, messageId);
    if (!persisted) throw new Error('Report idempotency race could not be reconciled');
    await this.repository.insertCommand(
      this.command(
        principal.accountId,
        idempotencyKey,
        'REPORT_MESSAGE',
        fingerprint,
        messageId,
        persisted.reportId,
        now,
      ),
    );
    if (inserted)
      await this.audit(
        principal,
        'REPORT_MESSAGE',
        'MESSAGE',
        messageId,
        reason,
        correlationId,
        now,
      );
    return this.reportReceipt(persisted.reportId, !inserted);
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
    const duplicate = await this.replay(
      principal.accountId,
      idempotencyKey,
      'RESOLVE_REPORT',
      fingerprint,
    );
    if (duplicate) return { reportId, state: 'RESOLVED' as const, resolution, duplicate: true };
    const report = await this.repository.findReport(reportId);
    if (!report) throw notFound();
    if (report.state === 'RESOLVED') {
      if (report.resolution !== resolution) throw conflict('REPORT_ALREADY_RESOLVED');
      await this.repository.insertCommand(
        this.command(
          principal.accountId,
          idempotencyKey,
          'RESOLVE_REPORT',
          fingerprint,
          reportId,
          reportId,
          new Date(),
        ),
      );
      return { reportId, state: 'RESOLVED' as const, resolution, duplicate: true };
    }
    const now = new Date();
    if (resolution === 'MESSAGE_REMOVED') {
      const message = await this.messages.tombstone(
        report.conversationId,
        report.messageId,
        undefined,
        now,
      );
      if (!message) throw notFound();
    }
    const resolved = await this.repository.resolve(reportId, principal.accountId, resolution, now);
    if (resolved?.resolution !== resolution) throw conflict('REPORT_ALREADY_RESOLVED');
    await this.repository.insertCommand(
      this.command(
        principal.accountId,
        idempotencyKey,
        'RESOLVE_REPORT',
        fingerprint,
        reportId,
        reportId,
        now,
      ),
    );
    await this.audit(principal, 'RESOLVE_REPORT', 'REPORT', reportId, reason, correlationId, now);
    return { reportId, state: 'RESOLVED' as const, resolution, duplicate: false };
  }

  private async replay(
    actorId: string,
    key: string,
    action: StoredModerationCommand['action'],
    fingerprint: string,
  ) {
    const command = await this.repository.findCommand(actorId, key);
    if (!command) return null;
    if (command.action !== action || command.fingerprint !== fingerprint)
      throw conflict('IDEMPOTENCY_CONFLICT');
    return command;
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
  ) {
    await this.repository.insertAudit({
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
    });
  }

  private reportReceipt(reportId: string, duplicate: boolean) {
    return { reportId, state: 'OPEN' as const, duplicate };
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
