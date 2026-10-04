import {
  Inject,
  Injectable,
  Logger,
  type OnApplicationShutdown,
  type OnModuleInit,
} from '@nestjs/common';
import { Kafka, logLevel, type Consumer } from 'kafkajs';
import { z } from 'zod';

import {
  APPOINTMENT_REMINDER_REPOSITORY_TOKEN,
  CONFIGURATION_TOKEN,
  REMINDER_CLOCK_TOKEN,
} from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { ReminderClock } from '../reminders/reminder-materialization.service.js';
import type { AppointmentReminderRepository } from './appointment-reminder.repository.js';
import type { AppointmentStatusChangedEvent } from './appointment-reminder.types.js';

export const APPOINTMENT_STATUS_TOPIC = 'mentalbridge.consultation.appointment-status.v1';

const EventSchema = z
  .object({
    messageId: z.uuid(),
    messageType: z.literal('consultation.appointment.status-changed'),
    occurredAt: z.iso.datetime({ offset: true }),
    producer: z.literal('consultation-service'),
    schemaVersion: z.literal('1.0'),
    correlationId: z.uuid(),
    aggregateId: z.uuid(),
    aggregateVersion: z.number().int().nonnegative(),
    payload: z
      .object({
        appointmentId: z.uuid(),
        ownerAccountId: z.uuid(),
        status: z.enum([
          'REQUESTED',
          'CONFIRMED',
          'REJECTED',
          'EXPIRED',
          'CANCELLED',
          'IN_PROGRESS',
          'SESSION_ENDED',
          'COMPLETED',
          'USER_NO_SHOW',
          'SPECIALIST_NO_SHOW',
          'DISPUTED',
        ]),
        scheduledStartAt: z.iso.datetime({ offset: true }),
        modality: z.enum(['IN_APP_CHAT', 'IN_APP_VIDEO']),
        replacesAppointmentId: z.uuid().nullable(),
      })
      .strict(),
  })
  .strict();

const EventEnvelopeSchema = z.object({ messageType: z.string() });

@Injectable()
export class AppointmentStatusProjector {
  constructor(
    @Inject(APPOINTMENT_REMINDER_REPOSITORY_TOKEN)
    private readonly repository: AppointmentReminderRepository,
    @Inject(REMINDER_CLOCK_TOKEN) private readonly clock: ReminderClock,
  ) {}

  async project(value: unknown): Promise<void> {
    const event = EventSchema.parse(value) as AppointmentStatusChangedEvent;
    if (event.aggregateId !== event.payload.appointmentId)
      throw new Error('Appointment aggregate identity mismatch');
    await this.repository.apply(event, this.clock.now());
  }
}

@Injectable()
export class AppointmentStatusConsumer implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(AppointmentStatusConsumer.name);
  private consumer?: Consumer;

  constructor(
    @Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration,
    private readonly projector: AppointmentStatusProjector,
  ) {}

  async onModuleInit(): Promise<void> {
    if (!this.configuration.CONTENT_APPOINTMENT_CONSUMER_ENABLED) return;
    const brokers =
      this.configuration.KAFKA_BOOTSTRAP_SERVERS?.split(',')
        .map((value) => value.trim())
        .filter(Boolean) ?? [];
    const kafka = new Kafka({
      clientId: 'content-notification-service',
      brokers,
      logLevel: logLevel.NOTHING,
    });
    this.consumer = kafka.consumer({ groupId: 'content-notification-appointment-reminders-v1' });
    await this.consumer.connect();
    await this.consumer.subscribe({ topic: APPOINTMENT_STATUS_TOPIC, fromBeginning: true });
    void this.consumer
      .run({
        eachMessage: async ({ message }) => {
          await this.handle(message.value);
        },
      })
      .catch(() => {
        this.logger.error('Appointment status consumer stopped unexpectedly');
      });
  }

  async handle(value: Buffer | null): Promise<void> {
    let parsed: unknown;
    try {
      parsed = JSON.parse(value?.toString('utf8') ?? '');
    } catch {
      this.logger.warn('Discarded an invalid appointment status event');
      return;
    }
    const envelope = EventEnvelopeSchema.safeParse(parsed);
    if (
      !envelope.success ||
      envelope.data.messageType !== 'consultation.appointment.status-changed'
    )
      return;
    const event = EventSchema.safeParse(parsed);
    if (!event.success || event.data.aggregateId !== event.data.payload.appointmentId) {
      this.logger.warn('Discarded an invalid appointment status event');
      return;
    }
    await this.projector.project(event.data);
  }

  async onApplicationShutdown(): Promise<void> {
    if (this.consumer) await this.consumer.disconnect();
  }
}
