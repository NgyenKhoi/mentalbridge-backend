import { Injectable } from '@nestjs/common';
import { MongoServerError } from 'mongodb';

import { MongoDatabaseService } from '../database/mongo-database.service.js';
import type {
  StoredAppointmentReport,
  StoredModerationAudit,
  StoredModerationCommand,
} from './appointment-moderation.types.js';

@Injectable()
export class AppointmentModerationRepository {
  constructor(private readonly database: MongoDatabaseService) {}

  findCommand(actorId: string, idempotencyKey: string) {
    return this.database
      .collection<StoredModerationCommand>('appointment_moderation_commands')
      .findOne({ actorId, idempotencyKey });
  }

  async insertCommand(command: StoredModerationCommand): Promise<boolean> {
    try {
      await this.database
        .collection<StoredModerationCommand>('appointment_moderation_commands')
        .insertOne(command);
      return true;
    } catch (error) {
      if (error instanceof MongoServerError && error.code === 11000) return false;
      throw error;
    }
  }

  findReport(reportId: string) {
    return this.database
      .collection<StoredAppointmentReport>('appointment_message_reports')
      .findOne({ reportId });
  }

  findReporterTarget(reporterId: string, messageId: string) {
    return this.database
      .collection<StoredAppointmentReport>('appointment_message_reports')
      .findOne({ reporterId, messageId });
  }

  async insertReport(report: StoredAppointmentReport): Promise<boolean> {
    try {
      await this.database
        .collection<StoredAppointmentReport>('appointment_message_reports')
        .insertOne(report);
      return true;
    } catch (error) {
      if (error instanceof MongoServerError && error.code === 11000) return false;
      throw error;
    }
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
        { returnDocument: 'after' },
      );
    return result ?? this.findReport(reportId);
  }

  insertAudit(audit: StoredModerationAudit) {
    return this.database
      .collection<StoredModerationAudit>('appointment_moderation_audit')
      .insertOne(audit);
  }
}
