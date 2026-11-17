import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { loadConfiguration, type AgentConfiguration } from "./configuration.js";

export interface AgentMeasurementSnapshot {
  schemaVersion: 1;
  component: "AGENT";
  capturedAt: string;
  enabled: boolean;
  pilotEnabled: boolean;
  ttsReservationEnabled: boolean;
  ttsCharactersPerAttempt: number | null;
  configuredTtsCharactersPerAttempt: number;
  finalAggregationMs: number;
  sttModel: string;
  ttsModel: string;
  voiceConfigured: boolean;
}

export function agentMeasurementSnapshot(
  configuration: AgentConfiguration,
  capturedAt = new Date(),
): AgentMeasurementSnapshot {
  const pilotEnabled = configuration.pilot === true;
  const configuredTtsCharactersPerAttempt =
    configuration.pilotTtsMaxCharacters ?? 1200;
  return {
    schemaVersion: 1,
    component: "AGENT",
    capturedAt: capturedAt.toISOString(),
    enabled: configuration.enabled,
    pilotEnabled,
    ttsReservationEnabled: pilotEnabled,
    ttsCharactersPerAttempt: pilotEnabled
      ? configuredTtsCharactersPerAttempt
      : null,
    configuredTtsCharactersPerAttempt,
    finalAggregationMs: configuration.finalAggregationMs ?? 750,
    sttModel: configuration.sttModel,
    ttsModel: configuration.ttsModel,
    voiceConfigured: configuration.voiceId !== null,
  };
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  try {
    process.stdout.write(
      `${JSON.stringify(agentMeasurementSnapshot(loadConfiguration(process.env)))}\n`,
    );
  } catch {
    process.stderr.write("VOICE_MEASUREMENT_PREFLIGHT_CONFIGURATION_INVALID\n");
    process.exitCode = 1;
  }
}
