import { z } from "zod";
import {
  corpus,
  devices,
  noises,
  regions,
  sttModels,
  denoiseModes,
} from "./corpus.js";
import { score, percentiles, BenchmarkFailure } from "./metrics.js";

const milliseconds = z.number().min(0).max(300000).nullable();
export const trialSchema = z.strictObject({
  scriptId: z.enum(corpus.map((item) => item.id)),
  model: z.enum(sttModels),
  device: z.enum(devices),
  noise: z.enum(noises),
  region: z.enum(regions),
  speakerToken: z.uuid(),
  consented: z.literal(true),
  source: z.enum(["human_scripted", "tts_loopback"]),
  denoise: z.enum(denoiseModes),
  finalTranscript: z.string().max(2000),
  sttFinalMs: milliseconds,
  ttsFirstFrameMs: milliseconds,
  firstAudibleMs: milliseconds,
  prematureEnd: z.boolean().nullable(),
  highImpactCorrect: z.boolean().nullable(),
  bargeInSuccess: z.boolean().nullable(),
  naturalness: z.number().int().min(1).max(5).nullable(),
  intelligibility: z.number().int().min(1).max(5).nullable(),
});
export type Trial = z.infer<typeof trialSchema>;
export const inputSchema = z.strictObject({
  sourceSha: z.string().regex(/^[0-9a-f]{40}$/u),
  measuredAt: z.iso.datetime(),
  voiceId: z.uuid().nullable(),
  trials: z.array(trialSchema).max(1000),
});
type Input = z.infer<typeof inputSchema>;
const totals = () => ({
  referenceUnits: 0,
  substitutions: 0,
  deletions: 0,
  insertions: 0,
});
const ratio = (value: ReturnType<typeof totals>) => ({
  ...value,
  rate: value.referenceUnits
    ? (value.substitutions + value.deletions + value.insertions) /
      value.referenceUnits
    : null,
});
const nonnull = (value: number | null): value is number => value !== null;
function summarize(trials: Trial[]) {
  const wer = totals();
  const cer = totals();
  let falseActivations = 0;
  let noiseOnlyTrials = 0;
  let scoredTrials = 0;
  for (const trial of trials) {
    const script = corpus.find((item) => item.id === trial.scriptId);
    if (!script) throw new BenchmarkFailure("INVALID_INPUT");
    const result = score(script.text, trial.finalTranscript);
    if (script.text) {
      scoredTrials++;
      for (const key of [
        "referenceUnits",
        "substitutions",
        "deletions",
        "insertions",
      ] as const) {
        wer[key] += result.wer[key];
        cer[key] += result.cer[key];
      }
    } else {
      noiseOnlyTrials++;
      if (trial.finalTranscript.trim()) falseActivations++;
    }
  }
  const observation = (
    key: "prematureEnd" | "highImpactCorrect" | "bargeInSuccess",
  ) => {
    const measured = trials
      .map((trial) => trial[key])
      .filter((value) => value !== null);
    return {
      measured: measured.length,
      positive: measured.filter(Boolean).length,
    };
  };
  const rating = (key: "naturalness" | "intelligibility") => {
    const values = trials.map((trial) => trial[key]).filter(nonnull);
    return {
      measured: values.length,
      mean: values.length
        ? values.reduce((a, b) => a + b, 0) / values.length
        : null,
    };
  };
  return {
    trials: trials.length,
    scoredTrials,
    noiseOnlyTrials,
    falseActivations,
    wer: ratio(wer),
    cer: ratio(cer),
    sttFinalLatency: percentiles(
      trials.map((t) => t.sttFinalMs).filter(nonnull),
    ),
    ttsFirstFrameLatency: percentiles(
      trials.map((t) => t.ttsFirstFrameMs).filter(nonnull),
    ),
    deviceFirstAudibleLatency: percentiles(
      trials.map((t) => t.firstAudibleMs).filter(nonnull),
    ),
    prematureEnd: observation("prematureEnd"),
    highImpactCorrect: observation("highImpactCorrect"),
    bargeInSuccess: observation("bargeInSuccess"),
    naturalness: rating("naturalness"),
    intelligibility: rating("intelligibility"),
  };
}
export function aggregate(input: unknown) {
  const parsed = inputSchema.safeParse(input);
  if (!parsed.success) throw new BenchmarkFailure("INVALID_INPUT");
  return buildReport(parsed.data);
}
function buildReport(input: Input) {
  const speakers = new Map<string, Trial["region"]>();
  const groups = new Map<
    string,
    {
      dimensions: Pick<
        Trial,
        "model" | "device" | "noise" | "region" | "source" | "denoise"
      >;
      trials: Trial[];
    }
  >();
  const measuredCells = new Set<string>();
  for (const trial of input.trials) {
    const script = corpus.find((item) => item.id === trial.scriptId);
    if (
      (!script?.highImpact && trial.highImpactCorrect !== null) ||
      (trial.source !== "human_scripted" && trial.firstAudibleMs !== null)
    )
      throw new BenchmarkFailure("INVALID_INPUT");
    if (trial.source === "human_scripted") {
      const priorRegion = speakers.get(trial.speakerToken);
      if (priorRegion && priorRegion !== trial.region)
        throw new BenchmarkFailure("INVALID_INPUT");
      speakers.set(trial.speakerToken, trial.region);
      if (script?.text && trial.denoise === "webrtc_baseline")
        measuredCells.add(
          JSON.stringify([
            trial.model,
            trial.device,
            trial.noise,
            trial.region,
          ]),
        );
    }
    const dimensions = {
      model: trial.model,
      device: trial.device,
      noise: trial.noise,
      region: trial.region,
      source: trial.source,
      denoise: trial.denoise,
    };
    const key = JSON.stringify(dimensions);
    const group = groups.get(key) ?? { dimensions, trials: [] };
    group.trials.push(trial);
    groups.set(key, group);
  }
  const missingCells = [];
  for (const model of sttModels)
    for (const device of devices)
      for (const noise of noises)
        for (const region of ["north", "central", "south"] as const) {
          if (
            !measuredCells.has(JSON.stringify([model, device, noise, region]))
          )
            missingCells.push({
              model,
              device,
              noise,
              region,
              status: "NOT_RUN" as const,
            });
        }
  const humanTrials = input.trials.filter((t) => t.source === "human_scripted");
  const human = summarize(humanTrials);
  const unsafe = humanTrials.some((t) => t.highImpactCorrect === false);
  const observedScripts = new Set(humanTrials.map((t) => t.scriptId));
  const missingScripts = corpus
    .filter((s) => !observedScripts.has(s.id))
    .map((s) => ({
      scriptId: s.id,
      condition: s.condition,
      status: "NOT_RUN" as const,
    }));
  return {
    schemaVersion: 1,
    task: "MB-760",
    sourceSha: input.sourceSha,
    measuredAt: input.measuredAt,
    ttsModel: "cartesia/sonic-3-2026-01-12",
    voiceId: input.voiceId,
    coverage: {
      speakers: speakers.size,
      speakersByRegion: Object.fromEntries(
        regions.map((region) => [
          region,
          [...speakers.values()].filter((r) => r === region).length,
        ]),
      ),
      missingCells,
      missingScripts,
      fullDialectAccuracyClaim: false,
    },
    recommendation: {
      status:
        humanTrials.length === 0
          ? "BLOCKED"
          : missingCells.length || missingScripts.length || unsafe
            ? "PROVISIONAL_REQUIRES_REVIEW"
            : "REQUIRES_OPERATOR_REVIEW",
      productionEnabled: false,
      highImpactMishearing: unsafe,
    },
    humanScripted: human,
    synthesizedLoopback: summarize(
      input.trials.filter((t) => t.source === "tts_loopback"),
    ),
    groups: [...groups.values()].map((group) => ({
      dimensions: group.dimensions,
      ...summarize(group.trials),
    })),
  };
}
