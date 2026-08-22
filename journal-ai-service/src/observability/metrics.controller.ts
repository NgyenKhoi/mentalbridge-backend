import { Controller, Get, Header, Inject } from "@nestjs/common";

import { Public } from "../security/public.decorator.js";
import type { Metrics } from "./metrics.js";
import { METRICS_TOKEN } from "./tokens.js";

@Public()
@Controller()
export class MetricsController {
  constructor(@Inject(METRICS_TOKEN) private readonly metrics: Metrics) {}

  @Get("metrics")
  @Header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
  metricsText(): Promise<string> {
    return this.metrics.render();
  }
}
