import { fileURLToPath } from "node:url";
import { MongoClient } from "mongodb";
import { loadConfiguration } from "../configuration/configuration.js";
import { MongoVoiceMeasurementStore } from "./voice-measurement-store.js";
import { measurementPolicy } from "./voice-measurement-policy.js";

export async function runMeasurementOperator(
  environment: NodeJS.ProcessEnv,
  action: string,
  write: (value: object) => void = (value) => {
    process.stdout.write(`${JSON.stringify(value)}\n`);
  },
): Promise<0 | 1 | 2> {
  if (
    environment.NODE_ENV !== "production" ||
    !["plan", "status", "provision"].includes(action)
  ) {
    write({
      schemaVersion: 1,
      status: "BLOCKED",
      liveApproved: false,
      code: "INVALID_OPERATOR_REQUEST",
    });
    return 1;
  }
  let client: MongoClient | undefined;
  try {
    const configuration = loadConfiguration(environment);
    if (!configuration.VOICE_PILOT?.measurement)
      throw new Error("MEASUREMENT_REQUIRED");
    if (action === "plan") {
      const policy = measurementPolicy(configuration.VOICE_PILOT);
      if (!policy) throw new Error("MEASUREMENT_REQUIRED");
      write({
        schemaVersion: 1,
        status: "BLOCKED",
        liveApproved: false,
        configHash: policy.configHash,
        caps: policy.caps,
        allocation: policy.allocation,
        codes: ["CONFIGURATION_ONLY_NOT_OWNER_ENFORCEMENT"],
      });
      return 2;
    }
    client = new MongoClient(configuration.MONGODB_URI, {
      serverSelectionTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
    });
    const store = new MongoVoiceMeasurementStore(
      client,
      configuration.MONGODB_DATABASE,
      configuration.VOICE_PILOT,
    );
    const now = new Date();
    if (action === "provision") await store.provision(now);
    const evidence = await store.preflight(now);
    write({
      schemaVersion: 1,
      component: "CAMPAIGN_OWNER",
      capturedAt: now.toISOString(),
      status: "BLOCKED",
      liveApproved: false,
      aggregateEnforcement: "BOUND_OWNER_RECORD",
      evidence,
      codes: [
        "FLEET_REVISION_BINDING_REQUIRED",
        "CURRENT_AUTHORITY_NOT_CHECKED",
        "CREDIT_AND_CLOUD_PRIVACY_NOT_CHECKED",
      ],
    });
    return 2;
  } catch {
    write({
      schemaVersion: 1,
      status: "BLOCKED",
      liveApproved: false,
      code: "MEASUREMENT_STATE_UNAVAILABLE",
    });
    return 1;
  } finally {
    await client?.close().catch(() => undefined);
  }
}
if (process.argv[1] === fileURLToPath(import.meta.url))
  process.exitCode = await runMeasurementOperator(
    process.env,
    process.argv[2] ?? "",
  );
