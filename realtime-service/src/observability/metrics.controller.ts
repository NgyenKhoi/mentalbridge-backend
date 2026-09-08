import { Controller, Get, Header, Inject } from '@nestjs/common';

import { METRICS_TOKEN } from '../shared/tokens.js';
import type { RealtimeMetrics } from './metrics.js';

@Controller()
export class MetricsController {
  constructor(@Inject(METRICS_TOKEN) private readonly realtimeMetrics: RealtimeMetrics) {}

  @Get('metrics')
  @Header('Content-Type', 'text/plain; version=0.0.4; charset=utf-8')
  metrics(): Promise<string> {
    return this.realtimeMetrics.render();
  }
}
