import { HttpStatus, Inject, Injectable } from '@nestjs/common';
import { z } from 'zod';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import { ApplicationException } from '../http/application.exception.js';
import { CONFIGURATION_TOKEN } from '../shared/tokens.js';

export interface ConversationEvidenceInput {
  readonly evidenceId: string;
  readonly type: 'CHECK_IN' | 'PRESENCE_INTERVAL' | 'ACCEPTED_MESSAGE';
  readonly occurredAt: string;
  readonly intervalStartedAt?: string;
  readonly messageId?: string;
}

const responseSchema = z
  .object({
    evidenceId: z.uuid(),
    accepted: z.boolean(),
    duplicate: z.boolean(),
    reasonCode: z.string().min(1).max(64),
    receivedAt: z.iso.datetime({ offset: true }),
  })
  .strict();

export interface ConversationEvidence {
  record(
    bearerToken: string,
    appointmentId: string,
    evidence: ConversationEvidenceInput,
    correlationId: string,
  ): Promise<void>;
}

@Injectable()
export class ConsultationConversationEvidence implements ConversationEvidence {
  constructor(@Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration) {}

  async record(
    bearerToken: string,
    appointmentId: string,
    evidence: ConversationEvidenceInput,
    correlationId: string,
  ): Promise<void> {
    let response: Response;
    try {
      response = await fetch(
        new URL(
          `/internal/v1/appointments/${encodeURIComponent(appointmentId)}/chat-evidence`,
          this.configuration.CONSULTATION_BASE_URL,
        ),
        {
          method: 'POST',
          headers: {
            authorization: `Bearer ${bearerToken}`,
            'content-type': 'application/json',
            'x-correlation-id': correlationId,
            'x-mentalbridge-service-token': this.configuration.CONSULTATION_EVIDENCE_SERVICE_TOKEN,
          },
          body: JSON.stringify(evidence),
          signal: AbortSignal.timeout(this.configuration.CONSULTATION_TIMEOUT_MS),
        },
      );
    } catch {
      throw unavailable();
    }
    if (response.status === 403 || response.status === 404) {
      throw new ApplicationException(
        HttpStatus.FORBIDDEN,
        'ACCESS_DENIED',
        'Conversation evidence access is denied',
      );
    }
    if (!response.ok) throw unavailable();
    let value: unknown;
    try {
      value = await response.json();
    } catch {
      throw unavailable();
    }
    const parsed = responseSchema.safeParse(value);
    if (!parsed.success || parsed.data.evidenceId !== evidence.evidenceId) throw unavailable();
    if (!parsed.data.accepted) {
      throw new ApplicationException(
        HttpStatus.CONFLICT,
        parsed.data.reasonCode,
        'Conversation evidence was not accepted',
        parsed.data.reasonCode === 'EVIDENCE_ID_CONFLICT',
      );
    }
  }
}

function unavailable(): ApplicationException {
  return new ApplicationException(
    HttpStatus.SERVICE_UNAVAILABLE,
    'CHAT_EVIDENCE_UNAVAILABLE',
    'Conversation evidence recording is unavailable',
    true,
  );
}
