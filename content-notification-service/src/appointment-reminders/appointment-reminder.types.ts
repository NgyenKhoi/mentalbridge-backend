export type AppointmentStatus =
  | 'REQUESTED'
  | 'CONFIRMED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'CANCELLED'
  | 'IN_PROGRESS'
  | 'SESSION_ENDED'
  | 'COMPLETED'
  | 'USER_NO_SHOW'
  | 'SPECIALIST_NO_SHOW'
  | 'DISPUTED';

export type AppointmentModality = 'IN_APP_CHAT' | 'IN_APP_VIDEO';

export interface AppointmentStatusChangedEvent {
  readonly messageId: string;
  readonly messageType: 'consultation.appointment.status-changed';
  readonly occurredAt: string;
  readonly producer: 'consultation-service';
  readonly schemaVersion: '1.0';
  readonly correlationId: string;
  readonly aggregateId: string;
  readonly aggregateVersion: number;
  readonly payload: {
    readonly appointmentId: string;
    readonly ownerAccountId: string;
    readonly status: AppointmentStatus;
    readonly scheduledStartAt: string;
    readonly modality: AppointmentModality;
    readonly replacesAppointmentId: string | null;
  };
}

export interface AppointmentReminderRow {
  readonly id: string;
  readonly recipient_id: string;
  readonly appointment_id: string;
  readonly appointment_version: string | number;
  readonly appointment_status: AppointmentStatus;
  readonly modality: AppointmentModality;
  readonly scheduled_start_at: Date | string;
  readonly target_at: Date | string;
  readonly due_at: Date | string;
  readonly attempt_count: number;
  readonly first_attempt_at: Date | string | null;
  readonly provider_idempotency_key: string;
}

export interface ClaimedAppointmentReminder {
  readonly id: string;
  readonly recipientId: string;
  readonly appointmentId: string;
  readonly appointmentVersion: number;
  readonly modality: AppointmentModality;
  readonly scheduledStartAt: Date;
  readonly targetAt: Date;
  readonly dueAt: Date;
  readonly attemptCount: number;
  readonly firstAttemptAt: Date;
  readonly providerIdempotencyKey: string;
}
