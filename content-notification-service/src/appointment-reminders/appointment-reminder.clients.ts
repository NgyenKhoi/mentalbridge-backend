import { Inject, Injectable } from '@nestjs/common';

import { CONFIGURATION_TOKEN } from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { AppointmentModality } from './appointment-reminder.types.js';

async function response(configuration: ServiceConfiguration, url: string): Promise<Response> {
  const controller = new AbortController();
  const timeout = setTimeout(() => {
    controller.abort();
  }, configuration.APPOINTMENT_REMINDER_HTTP_TIMEOUT_MS);
  try {
    return await fetch(url, {
      headers: {
        'x-mentalbridge-service-token': configuration.APPOINTMENT_REMINDER_SERVICE_TOKEN ?? '',
      },
      signal: controller.signal,
    });
  } finally {
    clearTimeout(timeout);
  }
}

export interface AppointmentEligibility {
  readonly appointmentId: string;
  readonly ownerAccountId: string;
  readonly version: number;
  readonly status: string;
  readonly scheduledStartAt: string;
  readonly modality: AppointmentModality;
  readonly eligible: boolean;
}

export interface AppointmentTruthClient {
  get(appointmentId: string, version: number): Promise<AppointmentEligibility>;
}

export interface DeliveryAddressClient {
  get(ownerId: string): Promise<string>;
}

@Injectable()
export class ConsultationAppointmentTruthClient implements AppointmentTruthClient {
  constructor(@Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration) {}

  async get(appointmentId: string, version: number): Promise<AppointmentEligibility> {
    const url = new URL(
      `/internal/v1/appointments/${appointmentId}/notification-eligibility`,
      this.configuration.CONSULTATION_SERVICE_URL,
    );
    url.searchParams.set('appointmentVersion', String(version));
    const result = await response(this.configuration, url.toString());
    if (!result.ok) throw new Error(`CONSULTATION_${String(result.status)}`);
    return (await result.json()) as AppointmentEligibility;
  }
}

@Injectable()
export class IdentityDeliveryAddressClient implements DeliveryAddressClient {
  constructor(@Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration) {}

  async get(ownerId: string): Promise<string> {
    const url = new URL(
      `/internal/v1/accounts/${ownerId}/verified-email`,
      this.configuration.IDENTITY_SERVICE_URL,
    );
    const result = await response(this.configuration, url.toString());
    if (!result.ok) throw new Error(`IDENTITY_${String(result.status)}`);
    const body = (await result.json()) as { accountId: string; email: string };
    if (body.accountId !== ownerId || !body.email) throw new Error('IDENTITY_OWNER_MISMATCH');
    return body.email;
  }
}
