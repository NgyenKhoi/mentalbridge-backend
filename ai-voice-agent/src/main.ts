import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
import {
  AgentServer,
  ServerOptions,
  WorkerPermissions,
  initializeLogger,
  type JobRequest,
} from "@livekit/agents";
import { TrackSource } from "@livekit/protocol";
import { loadConfiguration } from "./configuration.js";
import { agentName, parseDispatch } from "./contract.js";

export class DispatchAdmission {
  private readonly seen = new Map<string, number>();
  constructor(private readonly clock: () => number = Date.now) {}
  async accept(
    request: Pick<
      JobRequest,
      "agentName" | "job" | "room" | "accept" | "reject"
    >,
  ) {
    try {
      const room = request.room;
      const dispatch = parseDispatch({
        agentName: request.agentName,
        metadata: request.job.metadata,
        roomName: room?.name ?? "",
        roomMetadata: room?.metadata ?? "",
        recording: room?.activeRecording ?? false,
      });
      const key = createHash("sha256").update(dispatch.attemptId).digest("hex");
      for (const [key, expires] of this.seen)
        if (expires <= this.clock()) this.seen.delete(key);
      if (
        this.seen.has(key) ||
        this.seen.size >= 512 ||
        (room?.numParticipants ?? 0) >= 2
      ) {
        await request.reject();
        return;
      }
      this.seen.set(key, this.clock() + 600000);
      await request.accept("", `va-${dispatch.attemptId}`, "", {});
    } catch {
      await request.reject();
    }
  }
}
export async function run(environment: Record<string, string | undefined>) {
  const configuration = loadConfiguration(environment);
  if (
    !configuration.enabled ||
    !configuration.livekitUrl ||
    !configuration.apiKey ||
    !configuration.apiSecret
  )
    throw new Error("VOICE_DISABLED");
  initializeLogger({ pretty: false, level: "silent" });
  const admissions = new DispatchAdmission();
  const options = new ServerOptions({
    agent: fileURLToPath(new URL("agent.js", import.meta.url)),
    agentName,
    wsURL: configuration.livekitUrl,
    apiKey: configuration.apiKey,
    apiSecret: configuration.apiSecret,
    logLevel: "silent",
    numIdleProcesses: 0,
    maxRetry: 0,
    loadThreshold: 1,
    loadFunc: (server) =>
      Promise.resolve(server.activeJobs.length >= 2 ? 1 : 0),
    permissions: new WorkerPermissions(true, true, false, false, [
      TrackSource.MICROPHONE,
    ]),
    requestFunc: (request) => admissions.accept(request),
    production: environment.NODE_ENV === "production",
    shutdownProcessTimeout: 5000,
    drainTimeout: 10000,
  });
  const server = new AgentServer(options);
  const close = () => {
    void server.close().catch(() => {
      process.exitCode = 1;
    });
  };
  process.once("SIGINT", close);
  process.once("SIGTERM", close);
  try {
    await server.run();
  } finally {
    await server.close();
    process.off("SIGINT", close);
    process.off("SIGTERM", close);
  }
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  run(process.env).catch(() => {
    process.stderr.write("VOICE_AGENT_UNAVAILABLE\n");
    process.exitCode = 1;
  });
}
