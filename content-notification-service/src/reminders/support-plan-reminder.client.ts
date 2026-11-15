import { Inject, Injectable, ServiceUnavailableException } from '@nestjs/common';
import { z } from 'zod';

import { CONFIGURATION_TOKEN } from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';

const EligibilitySchema = z.object({ eligible: z.boolean() }).strict();

@Injectable()
export class SupportPlanReminderClient {
  constructor(
    @Inject(CONFIGURATION_TOKEN)
    private readonly configuration: ServiceConfiguration,
  ) {}

  isConfigured(): boolean {
    return Boolean(this.configuration.CONTENT_CARE_SUPPORT_PLAN_REMINDER_SERVICE_TOKEN);
  }

  async eligible(ownerAccountId: string, asOf: Date, correlationId: string): Promise<boolean> {
    const serviceToken = this.configuration.CONTENT_CARE_SUPPORT_PLAN_REMINDER_SERVICE_TOKEN;
    if (!serviceToken) throw new ServiceUnavailableException();
    const controller = new AbortController();
    const timeout = setTimeout(() => {
      controller.abort();
    }, this.configuration.CONTENT_CARE_TIMEOUT_MS ?? 2_000);
    try {
      const response = await fetch(
        new URL(
          '/internal/v1/support-plan-reminder-eligibility',
          this.configuration.CONTENT_CARE_BASE_URL ?? 'http://localhost:8081',
        ),
        {
          method: 'POST',
          redirect: 'error',
          signal: controller.signal,
          headers: {
            Accept: 'application/json',
            'Content-Type': 'application/json',
            'X-MentalBridge-Service-Token': serviceToken,
            'X-Correlation-Id': correlationId,
          },
          body: JSON.stringify({ ownerAccountId, asOf: asOf.toISOString() }),
        },
      );
      if (!response.ok) throw new ServiceUnavailableException();
      return EligibilitySchema.parse(await response.json()).eligible;
    } catch (error) {
      if (error instanceof ServiceUnavailableException) throw error;
      throw new ServiceUnavailableException({ cause: error });
    } finally {
      clearTimeout(timeout);
    }
  }
}
