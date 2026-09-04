import {
  collectDefaultMetrics,
  Counter,
  Gauge,
  Histogram,
  Registry,
  type Counter as CounterType,
  type Gauge as GaugeType,
  type Histogram as HistogramType,
} from 'prom-client';

import type { ServiceConfiguration } from '../configuration/configuration.js';

export interface RealtimeMetrics {
  readonly activeSockets: GaugeType;
  readonly socketConnections: CounterType;
  readonly commands: CounterType;
  readonly dependencyChecks: CounterType;
  readonly messagePersistSeconds: HistogramType;
  render(): Promise<string>;
}

export const createMetrics = (configuration: ServiceConfiguration): RealtimeMetrics => {
  const registry = new Registry();
  registry.setDefaultLabels({ service: configuration.SERVICE_NAME });
  collectDefaultMetrics({ register: registry });
  return {
    activeSockets: new Gauge({
      name: 'realtime_active_sockets',
      help: 'Current authenticated Socket.IO connections.',
      registers: [registry],
    }),
    socketConnections: new Counter({
      name: 'realtime_socket_connections_total',
      help: 'Socket connection outcomes.',
      labelNames: ['result'],
      registers: [registry],
    }),
    commands: new Counter({
      name: 'realtime_commands_total',
      help: 'Realtime command outcomes.',
      labelNames: ['command', 'result'],
      registers: [registry],
    }),
    dependencyChecks: new Counter({
      name: 'realtime_dependency_checks_total',
      help: 'Dependency health check outcomes.',
      labelNames: ['dependency', 'result'],
      registers: [registry],
    }),
    messagePersistSeconds: new Histogram({
      name: 'realtime_message_persist_seconds',
      help: 'MongoDB message persistence latency.',
      registers: [registry],
    }),
    render: () => registry.metrics(),
  };
};
