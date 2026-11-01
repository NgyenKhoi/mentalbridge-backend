import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import { corpus, sttModels, ttsModel } from "./corpus.js";
import { aggregate, type Trial } from "./report.js";
import { BenchmarkFailure } from "./metrics.js";
import {
  PilotBudget,
  operatorSnapshot,
  readiness,
  priceSnapshot,
} from "./pilot.js";

type Observation = Pick<
  Trial,
  "finalTranscript" | "sttFinalMs" | "ttsFirstFrameMs"
>;
type MockProvider = (text: string, signal: AbortSignal) => Promise<Observation>;
const fake: MockProvider = (text) =>
  Promise.resolve({
    finalTranscript: text,
    sttFinalMs: 250,
    ttsFirstFrameMs: text ? 350 : null,
  });
export async function mockPilot(
  sourceSha: string,
  provider: MockProvider = fake,
  timeoutMs = 1000,
) {
  if (!/^[0-9a-f]{40}$/u.test(sourceSha))
    throw new BenchmarkFailure("INVALID_INPUT");
  const budget = new PilotBudget();
  const trials: Trial[] = [];
  for (const model of sttModels)
    for (const script of corpus) {
      budget.reserve({ kind: "STT", model, audioMs: 10000 });
      if (script.text)
        budget.reserve({
          kind: "TTS",
          model: ttsModel,
          characters: script.text.length,
        });
      const abort = new AbortController();
      let observation: Observation;
      try {
        observation = await Promise.race([
          provider(script.text, abort.signal),
          delay(timeoutMs, undefined, { signal: abort.signal }).then(() => {
            throw new BenchmarkFailure("PROVIDER_FAILED");
          }),
        ]);
      } catch {
        throw new BenchmarkFailure("PROVIDER_FAILED");
      } finally {
        abort.abort();
      }
      trials.push({
        ...observation,
        scriptId: script.id,
        model,
        device: "laptop",
        noise: "quiet",
        region: "other",
        source: "mock",
        denoise: "webrtc_baseline",
        speakerToken: randomUUID(),
        consented: true,
        firstAudibleMs: null,
        prematureEnd: null,
        highImpactCorrect: null,
        bargeInSuccess: null,
        naturalness: null,
        intelligibility: null,
      });
    }
  return {
    mode: "MOCK_ONLY",
    providerCalls: 0,
    actualSpendMicroUsd: 0,
    actualDevicesTested: [],
    consentingHumanSpeakers: 0,
    regionsTested: 0,
    readiness: readiness(operatorSnapshot),
    simulatedReservations: budget.snapshot(),
    indicativePriceSnapshot: priceSnapshot,
    evaluation: aggregate({
      sourceSha,
      measuredAt: new Date().toISOString(),
      voiceId: null,
      trials,
    }),
  };
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.length !== 2 || args[0] !== "--source-sha") {
    process.stderr.write("MB760_INVALID_INPUT\n");
    process.exitCode = 1;
  } else {
    mockPilot(args[1] ?? "")
      .then((report) => {
        process.stdout.write(JSON.stringify(report) + "\n");
      })
      .catch(() => {
        process.stderr.write("MB760_MOCK_FAILED\n");
        process.exitCode = 1;
      });
  }
}
