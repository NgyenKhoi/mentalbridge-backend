import { HttpStatus, Inject, Injectable } from '@nestjs/common';
import { z } from 'zod';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import { ApplicationException } from '../http/application.exception.js';
import { CONFIGURATION_TOKEN } from '../shared/tokens.js';

export type ConversationOperation = 'subscribe' | 'send' | 'history';

const eligibilitySchema = z
  .object({
    conversationId: z.uuid(),
    appointmentId: z.uuid(),
    userAccountId: z.uuid(),
    specialistAccountId: z.uuid(),
    phase: z.enum([
      'NOT_AVAILABLE',
      'TOO_EARLY',
      'WAITING',
      'ACTIVE',
      'ENDED',
      'CANCELLED',
      'RESCHEDULED',
    ]),
    reasonCode: z.enum([
      'APPOINTMENT_NOT_CONFIRMED',
      'CHAT_ENTRY_TOO_EARLY',
      'APPOINTMENT_WAITING',
      'APPOINTMENT_ACTIVE',
      'APPOINTMENT_ENDED',
      'APPOINTMENT_CANCELLED',
      'APPOINTMENT_RESCHEDULED',
    ]),
    subscribeAllowed: z.boolean(),
    sendAllowed: z.boolean(),
    historyAllowed: z.boolean(),
    scheduledStartAt: z.iso.datetime({ offset: true }),
    scheduledEndAt: z.iso.datetime({ offset: true }),
    serverTime: z.iso.datetime({ offset: true }),
  })
  .strict();

export type ConversationEligibilityDecision = z.infer<typeof eligibilitySchema>;

export interface ConversationEligibility {
  check(
    bearerToken: string,
    accountId: string,
    conversationId: string,
    operation: ConversationOperation,
    correlationId: string,
  ): Promise<ConversationEligibilityDecision>;
}

@Injectable()
export class ConsultationConversationEligibility implements ConversationEligibility {
  constructor(@Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration) {}

  async check(
    bearerToken: string,
    accountId: string,
    conversationId: string,
    operation: ConversationOperation,
    correlationId: string,
  ): Promise<ConversationEligibilityDecision> {
    let response: Response;
    try {
      const endpoint = new URL(
        `/internal/v1/appointments/${encodeURIComponent(conversationId)}/chat-eligibility`,
        this.configuration.CONSULTATION_BASE_URL,
      );
      endpoint.searchParams.set('operation', operation.toUpperCase());
      response = await fetch(endpoint, {
        headers: {
          authorization: `Bearer ${bearerToken}`,
          'x-correlation-id': correlationId,
        },
        signal: AbortSignal.timeout(this.configuration.CONSULTATION_TIMEOUT_MS),
      });
    } catch {
      throw unavailable();
    }
    if (response.status === 403 || response.status === 404) {
      throw new ApplicationException(
        HttpStatus.FORBIDDEN,
        'ACCESS_DENIED',
        'Conversation access is denied',
      );
    }
    if (!response.ok) throw unavailable();
    let value: unknown;
    try {
      value = await response.json();
    } catch {
      throw unavailable();
    }
    const parsed = eligibilitySchema.safeParse(value);
    if (!parsed.success || parsed.data.conversationId !== conversationId) throw unavailable();
    if (parsed.data.userAccountId !== accountId && parsed.data.specialistAccountId !== accountId) {
      throw new ApplicationException(
        HttpStatus.FORBIDDEN,
        'ACCESS_DENIED',
        'Conversation access is denied',
      );
    }
    const allowed =
      operation === 'subscribe'
        ? parsed.data.subscribeAllowed
        : operation === 'send'
          ? parsed.data.sendAllowed
          : parsed.data.historyAllowed;
    if (!allowed) throw denied(parsed.data.reasonCode);
    return parsed.data;
  }
}

const reasonCodes: Readonly<Record<string, string>> = {
  APPOINTMENT_NOT_CONFIRMED: 'APPOINTMENT_NOT_CONFIRMED',
  CHAT_ENTRY_TOO_EARLY: 'CHAT_NOT_STARTED',
  APPOINTMENT_WAITING: 'CHAT_NOT_STARTED',
  APPOINTMENT_ENDED: 'CHAT_ENDED',
  APPOINTMENT_CANCELLED: 'CHAT_CANCELLED',
  APPOINTMENT_RESCHEDULED: 'CHAT_RESCHEDULED',
};

function denied(reasonCode: string): ApplicationException {
  return new ApplicationException(
    HttpStatus.FORBIDDEN,
    reasonCodes[reasonCode] ?? 'ACCESS_DENIED',
    'Conversation operation is not allowed',
  );
}

function unavailable(): ApplicationException {
  return new ApplicationException(
    HttpStatus.SERVICE_UNAVAILABLE,
    'CHAT_ELIGIBILITY_UNAVAILABLE',
    'Conversation eligibility is unavailable',
    true,
  );
}
