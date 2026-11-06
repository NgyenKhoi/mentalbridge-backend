import { Inject, Injectable, type OnApplicationShutdown } from '@nestjs/common';
import { Agent, fetch } from 'undici';
import { z } from 'zod';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { RealtimeMetrics } from '../observability/metrics.js';
import { CONFIGURATION_TOKEN, METRICS_TOKEN } from '../shared/tokens.js';

const eligibilitySchema = z.object({ notificationId: z.uuid() }).strict();
export type NotificationEligibility = 'eligible' | 'ineligible' | 'unavailable';

@Injectable()
export class ContentNotificationClient implements OnApplicationShutdown {
  private readonly dispatcher = new Agent({
    connectTimeout: 500,
    connections: 32,
    pipelining: 1,
    maxResponseSize: 1024,
  });
  private failures = 0;
  private openUntil = 0;

  constructor(
    @Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration,
    @Inject(METRICS_TOKEN) private readonly metrics: RealtimeMetrics,
  ) {}

  async check(
    bearerToken: string,
    notificationId: string,
    correlationId: string,
    remainingMs: number,
  ): Promise<NotificationEligibility> {
    if (Date.now() < this.openUntil || remainingMs <= 0) return 'unavailable';
    try {
      const response = await fetch(
        new URL(
          `/internal/v1/notifications/${encodeURIComponent(notificationId)}/live-eligibility`,
          this.configuration.SPECIALIST_LIVE.baseUrl,
        ),
        {
          headers: { authorization: `Bearer ${bearerToken}`, 'x-correlation-id': correlationId },
          redirect: 'error',
          dispatcher: this.dispatcher,
          signal: AbortSignal.timeout(
            Math.max(1, Math.min(this.configuration.SPECIALIST_LIVE.timeoutMs, remainingMs)),
          ),
        },
      );
      if (response.status !== 200) {
        await response.body?.cancel();
        if ([401, 403, 404, 422].includes(response.status)) {
          this.failures = 0;
          this.metrics.commands.inc({ command: 'notification.owner', result: 'ineligible' });
          return 'ineligible';
        }
        throw new Error('Owner unavailable');
      }
      const text = await response.text();
      if (Buffer.byteLength(text) > 1024) throw new Error('Owner response too large');
      const input: unknown = JSON.parse(text);
      const parsed = eligibilitySchema.safeParse(input);
      if (!parsed.success || parsed.data.notificationId !== notificationId)
        throw new Error('Owner response invalid');
      this.failures = 0;
      this.metrics.commands.inc({ command: 'notification.owner', result: 'eligible' });
      return 'eligible';
    } catch {
      if (++this.failures >= 5) {
        this.openUntil = Date.now() + 2000;
        this.failures = 0;
      }
      this.metrics.commands.inc({ command: 'notification.owner', result: 'unavailable' });
      return 'unavailable';
    }
  }

  async onApplicationShutdown(): Promise<void> {
    await this.dispatcher.close();
  }
}
