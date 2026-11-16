import { fileURLToPath } from "node:url";
import {
  loadConfiguration,
  type ServiceConfiguration,
} from "../configuration/configuration.js";
import { effectiveBedrockRequestLimits } from "../llm-providers/bedrock-request-limits.js";

export interface JournalVoiceMeasurementSnapshot {
  schemaVersion: 1;
  component: "JOURNAL";
  capturedAt: string;
  voiceEnabled: boolean;
  providerEnabled: boolean;
  inputGuardEnabled: boolean;
  inputTokenLimit: 1900 | null;
  outputTokenLimit: number;
  pilot: {
    startsAt: string;
    endsAt: string;
    ownerCount: 1 | 2;
    maxSessionSeconds: number;
    maxTurnsPerSession: number;
  } | null;
  concurrencyLimit: 2;
}

export function journalVoiceMeasurementSnapshot(
  configuration: ServiceConfiguration,
  now = new Date(),
): JournalVoiceMeasurementSnapshot {
  const limits = effectiveBedrockRequestLimits(configuration, true);
  const pilot = configuration.VOICE_PILOT;
  const ownerCount = pilot?.ownerIds.length;
  if (pilot && ownerCount !== 1 && ownerCount !== 2)
    throw new Error("CONFIGURATION_INVALID");
  return {
    schemaVersion: 1,
    component: "JOURNAL",
    capturedAt: now.toISOString(),
    voiceEnabled: configuration.VOICE_MEDIA?.enabled === true,
    providerEnabled: configuration.VOICE_BEDROCK_ROUTE != null,
    inputGuardEnabled: limits.inputGuardEnabled,
    inputTokenLimit: limits.inputTokenLimit,
    outputTokenLimit: limits.outputTokenLimit,
    pilot: pilot
      ? {
          startsAt: pilot.startsAt.toISOString(),
          endsAt: pilot.endsAt.toISOString(),
          ownerCount: ownerCount as 1 | 2,
          maxSessionSeconds: pilot.maxSessionSeconds ?? 90,
          maxTurnsPerSession: pilot.maxTurns ?? 4,
        }
      : null,
    concurrencyLimit: 2,
  };
}

export function runJournalVoiceMeasurementSnapshot(
  environment: NodeJS.ProcessEnv,
  write: (value: object) => void = (value) => {
    process.stdout.write(`${JSON.stringify(value)}\n`);
  },
): 0 | 1 {
  if (environment.NODE_ENV !== "production") {
    write({ code: "PRODUCTION_ENV_REQUIRED" });
    return 1;
  }
  try {
    const configuration = loadConfiguration(environment);
    write(journalVoiceMeasurementSnapshot(configuration));
    return 0;
  } catch {
    write({ code: "CONFIGURATION_INVALID" });
    return 1;
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.exitCode = runJournalVoiceMeasurementSnapshot(process.env);
}
