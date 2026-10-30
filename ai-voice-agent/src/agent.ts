import {
  defineAgent,
  initializeLogger,
  type JobContext,
} from "@livekit/agents";
import { loadConfiguration } from "./configuration.js";
import { parseDispatch } from "./contract.js";
import { JournalClient } from "./journal-client.js";
import { LiveKitMedia } from "./livekit-media.js";
import { VoiceWorker, startWorkerTimer } from "./voice-worker.js";
import { agentDiagnostics } from "./diagnostics.js";
export default defineAgent({
  entry: async (context: JobContext) => {
    initializeLogger({ pretty: false, level: "silent" });
    const configuration = loadConfiguration(process.env);
    if (!configuration.enabled) {
      context.shutdown("VOICE_DISABLED");
      return;
    }
    let worker: VoiceWorker | undefined;
    let stopTimer: (() => void) | undefined;
    try {
      const dispatch = parseDispatch({
        agentName: context.job.agentName,
        metadata: context.job.metadata,
        roomName: context.job.room?.name ?? "",
        roomMetadata: context.job.room?.metadata ?? "",
        recording: context.job.room?.activeRecording ?? false,
      });
      const diagnostic = agentDiagnostics(dispatch.attemptId);
      worker = new VoiceWorker(
        dispatch,
        new JournalClient(configuration),
        new LiveKitMedia(context, dispatch, configuration, diagnostic),
        Date.now,
        undefined,
        diagnostic,
      );
      const active = worker;
      context.addShutdownCallback(() => active.stop("AGENT_FAILURE"));
      stopTimer = startWorkerTimer(active);
      await active.start();
      await active.finished;
    } catch {
      await worker?.stop("AGENT_FAILURE");
    } finally {
      stopTimer?.();
      context.shutdown("VOICE_FINISHED");
    }
  },
});
