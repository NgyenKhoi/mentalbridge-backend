import { Injectable } from '@nestjs/common';
import type { ClientSession, ObjectId } from 'mongodb';

import { MongoDatabaseService } from '../database/mongo-database.service.js';
import type {
  StoredAppointmentReport,
  StoredModerationAudit,
  StoredModerationCommand,
} from './appointment-moderation.types.js';

@Injectable()
export class AppointmentModerationRepository {
  constructor(private readonly database: MongoDatabaseService) {}

  withTransaction<T>(work: (session: ClientSession) => Promise<T>) {
    return this.database.withTransaction(work);
  }

  findCommand(actorId: string, idempotencyKey: string, session?: ClientSession) {
    return this.database
      .collection<StoredModerationCommand>('appointment_moderation_commands')
      .findOne({ actorId, idempotencyKey }, session ? { session } : {});
  }

  async insertCommand(command: StoredModerationCommand, session: ClientSession): Promise<void> {
    await this.database
      .collection<StoredModerationCommand>('appointment_moderation_commands')
      .insertOne(command, { session });
  }

  async completeCommand(
    id: ObjectId,
    resultId: string,
    resultState: NonNullable<StoredModerationCommand['resultState']>,
    resultTimestamp: Date | null,
    session: ClientSession,
  ): Promise<void> {
    const result = await this.database
      .collection<StoredModerationCommand>('appointment_moderation_commands')
      .updateOne(
        { _id: id, resultState: null },
        { $set: { resultId, resultState, resultTimestamp } },
        { session },
      );
    if (result.modifiedCount !== 1) throw new Error('Moderation command completion was not stored');
  }

  findReport(reportId: string, session?: ClientSession) {
    return this.database
      .collection<StoredAppointmentReport>('appointment_message_reports')
      .findOne({ reportId }, session ? { session } : {});
  }

  findReporterTarget(reporterId: string, messageId: string, session?: ClientSession) {
    return this.database
      .collection<StoredAppointmentReport>('appointment_message_reports')
      .findOne({ reporterId, messageId }, session ? { session } : {});
  }

  async insertReport(report: StoredAppointmentReport, session: ClientSession): Promise<void> {
    await this.database
      .collection<StoredAppointmentReport>('appointment_message_reports')
      .insertOne(report, { session });
  }

  async listReports(state: 'OPEN' | 'RESOLVED' | undefined, limit: number) {
    return this.database
      .collection<StoredAppointmentReport>('appointment_message_reports')
      .find(state ? { state } : {})
      .sort({ createdAt: -1, _id: -1 })
      .limit(limit)
      .toArray();
  }

  async resolve(
    reportId: string,
    actorId: string,
    resolution: 'NO_ACTION' | 'MESSAGE_REMOVED',
    resolvedAt: Date,
    session: ClientSession,
  ) {
    const result = await this.database
      .collection<StoredAppointmentReport>('appointment_message_reports')
      .findOneAndUpdate(
        { reportId, state: 'OPEN' },
        {
          $set: {
            state: 'RESOLVED',
            resolution,
            resolvedBy: actorId,
            resolvedAt,
            updatedAt: resolvedAt,
          },
        },
        { returnDocument: 'after', session },
      );
    return result ?? this.findReport(reportId, session);
  }

  insertAudit(audit: StoredModerationAudit, session: ClientSession) {
    return this.database
      .collection<StoredModerationAudit>('appointment_moderation_audit')
      .insertOne(audit, { session });
  }
}
