import { Inject, Injectable, ServiceUnavailableException } from '@nestjs/common';
import { z } from 'zod';

import { CONFIGURATION_TOKEN } from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { PlanResourceActions } from './resource-engagement.types.js';

const VersionSchema = z
  .string()
  .regex(/^(0|[1-9]\d*)$/)
  .max(19);
const ProjectionSchema = z
  .object({
    planState: z.enum(['ACTIVE', 'PAUSED', 'NONE']),
    supportPlanId: z.uuid().optional(),
    supportPlanVersion: z.number().int().min(0).transform(String).optional(),
    timeZone: z.string().optional(),
    actions: z.array(
      z.object({
        actionId: z.string().min(1).max(128),
        actionVersion: z.number().int().min(0).transform(String),
        resource: z.object({
          resourceId: z.uuid(),
          resourceVersion: VersionSchema,
          publicationId: z.uuid(),
        }),
      }),
    ),
  })
  .superRefine((value, context) => {
    if (
      value.planState !== 'NONE' &&
      (!value.supportPlanId || !value.supportPlanVersion || !value.timeZone)
    ) {
      context.addIssue({ code: 'custom', message: 'Current plan metadata is required' });
    }
    if (value.planState === 'NONE' && value.actions.length > 0) {
      context.addIssue({ code: 'custom', message: 'Absent plan cannot contain actions' });
    }
  });

@Injectable()
export class CareResourceActionsClient {
  constructor(@Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration) {}

  async get(
    authorization: string,
    localDate: string,
    correlationId?: string,
  ): Promise<PlanResourceActions> {
    const controller = new AbortController();
    const timeout = setTimeout(() => {
      controller.abort();
    }, this.configuration.CONTENT_CARE_TIMEOUT_MS ?? 2_000);
    try {
      const url = new URL(
        '/api/v2/support-plans/current/resource-actions',
        this.configuration.CONTENT_CARE_BASE_URL ?? 'http://localhost:8081',
      );
      url.searchParams.set('localDate', localDate);
      const response = await fetch(url, {
        method: 'GET',
        redirect: 'error',
        signal: controller.signal,
        headers: {
          Accept: 'application/json',
          Authorization: authorization,
          ...(correlationId ? { 'X-Correlation-Id': correlationId } : {}),
        },
      });
      if (!response.ok) throw new Error('Care projection unavailable');
      return ProjectionSchema.parse(await response.json());
    } catch {
      throw new ServiceUnavailableException({
        type: 'https://mentalbridge.io/errors/RESOURCE_BINGO_CARE_UNAVAILABLE',
        title: 'Current plan resources are unavailable',
        status: 503,
        code: 'RESOURCE_BINGO_CARE_UNAVAILABLE',
      });
    } finally {
      clearTimeout(timeout);
    }
  }
}
