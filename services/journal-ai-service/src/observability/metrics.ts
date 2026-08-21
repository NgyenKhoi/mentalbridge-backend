import {
  collectDefaultMetrics,
  Registry,
  type Counter,
  Counter as PrometheusCounter,
} from "prom-client";

import type { ServiceConfiguration } from "../configuration/configuration.js";

export interface Metrics {
  readonly contentType: string;
  readonly healthChecksTotal: Counter;
  render(): Promise<string>;
}

export const createMetrics = (configuration: ServiceConfiguration): Metrics => {
  const registry = new Registry();

  registry.setDefaultLabels({
    service: configuration.SERVICE_NAME,
  });

  collectDefaultMetrics({ register: registry });

  const healthChecksTotal = new PrometheusCounter({
    name: "journal_ai_health_checks_total",
    help: "Total Journal-AI health checks by endpoint.",
    labelNames: ["endpoint"],
    registers: [registry],
  });

  return {
    contentType: registry.contentType,
    healthChecksTotal,
    render: () => registry.metrics(),
  };
};
