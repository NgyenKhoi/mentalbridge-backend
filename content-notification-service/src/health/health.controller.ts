import { Controller, Get, Inject, ServiceUnavailableException } from '@nestjs/common';

import { READINESS_PROBE_TOKEN } from '../application.tokens.js';
import type { ReadinessProbe } from '../database/database.service.js';

interface LiveResponse {
  readonly status: 'ok';
}

interface ReadyResponse extends LiveResponse {
  readonly db: 'connected';
}

@Controller('health')
export class HealthController {
  constructor(
    @Inject(READINESS_PROBE_TOKEN)
    private readonly readinessProbe: ReadinessProbe,
  ) {}

  @Get('live')
  live(): LiveResponse {
    return { status: 'ok' };
  }

  @Get('ready')
  async ready(): Promise<ReadyResponse> {
    try {
      await this.readinessProbe.check();
    } catch {
      throw new ServiceUnavailableException('PostgreSQL is unavailable');
    }

    return { status: 'ok', db: 'connected' };
  }
}
