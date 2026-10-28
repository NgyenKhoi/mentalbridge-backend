import {
  defineAgent,
  initializeLogger,
  type JobContext,
} from "@livekit/agents";
import { loadConfiguration } from "./configuration.js";
import { parseDispatch } from "./contract.js";
import { JournalClient } from "./journal-client.js";
import { LiveKitMedia } from "./livekit-media.js";
import { VoiceWorker } from "./voice-worker.js";
export default defineAgent({
  entry: async (context: JobContext) => {
    initializeLogger({ pretty: false, level: "silent" });
    const configuration = loadConfiguration(process.env);
    if (!configuration.enabled) {
      context.shutdown("VOICE_DISABLED");
      return;
    }
    let worker: VoiceWorker | undefined;
    let timer: ReturnType<typeof setInterval> | undefined;
    try {
      const dispatch = parseDispatch({
        agentName: context.job.agentName,
        metadata: context.job.metadata,
        roomName: context.job.room?.name ?? "",
        roomMetadata: context.job.room?.metadata ?? "",
        recording: context.job.room?.activeRecording ?? false,
      });
      worker = new VoiceWorker(
        dispatch,
        new JournalClient(configuration),
        new LiveKitMedia(context, dispatch, configuration),
      );
      const active = worker;
      context.addShutdownCallback(() => active.stop("AGENT_FAILURE"));
      timer = setInterval(() => {
        void active.tick().catch(() => active.fail());
      }, 250);
      timer.unref();
      await active.start();
      await active.finished;
    } catch {
      await worker?.stop("AGENT_FAILURE");
    } finally {
      if (timer) clearInterval(timer);
      context.shutdown("VOICE_FINISHED");
    }
  },
});
