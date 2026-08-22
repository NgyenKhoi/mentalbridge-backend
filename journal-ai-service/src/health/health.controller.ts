import {
  Controller,
  Get,
  Inject,
  ServiceUnavailableException,
} from "@nestjs/common";

import type { ServiceConfiguration } from "../configuration/configuration.js";
import type { Metrics } from "../observability/metrics.js";
import {
  CONFIGURATION_TOKEN,
  METRICS_TOKEN,
  READINESS_PROBE_TOKEN,
} from "../observability/tokens.js";
import { Public } from "../security/public.decorator.js";
import type { ReadinessProbe } from "./readiness.js";

interface HealthResponse {
  readonly status: "ok";
  readonly service: string;
  readonly environment: ServiceConfiguration["NODE_ENV"];
}

@Public()
@Controller("health")
export class HealthController {
  constructor(
    @Inject(CONFIGURATION_TOKEN)
    private readonly configuration: ServiceConfiguration,
    @Inject(METRICS_TOKEN)
    private readonly metrics: Metrics,
    @Inject(READINESS_PROBE_TOKEN)
    private readonly readinessProbe: ReadinessProbe,
  ) {}

  @Get("live")
  live(): HealthResponse {
    this.metrics.healthChecksTotal.inc({ endpoint: "live", result: "ok" });
    return this.response();
  }

  @Get("ready")
  async ready(): Promise<HealthResponse> {
    try {
      await this.readinessProbe.check();
      this.metrics.healthChecksTotal.inc({ endpoint: "ready", result: "ok" });
    } catch {
      this.metrics.healthChecksTotal.inc({
        endpoint: "ready",
        result: "unavailable",
      });
      throw new ServiceUnavailableException("MongoDB is unavailable");
    }

    return this.response();
  }

  private response(): HealthResponse {
    return {
      status: "ok",
      service: this.configuration.SERVICE_NAME,
      environment: this.configuration.NODE_ENV,
    };
  }
}
