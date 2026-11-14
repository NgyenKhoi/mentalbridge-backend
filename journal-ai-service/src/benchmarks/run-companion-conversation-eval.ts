import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { setTimeout } from "node:timers/promises";
import { loadConfiguration } from "../configuration/configuration.js";
import {
  companionMaxOutputTokens,
  RoutedChatProvider,
  type ChatProvider,
  type ChatRoute,
} from "../companion-chat/companion-chat.js";
import {
  COMPANION_GUIDANCE_V2_VERSION,
  companionGuidanceSystem,
} from "../companion-chat/companion-guidance-policy.js";
import { ResourceCatalog } from "../companion-chat/resource-catalog.js";
import { bedrockConverseUrl } from "../llm-providers/bedrock.js";
import {
  arcPassRates,
  callJudge,
  capRefusal,
  ARC_PASS_RATE_GATES,
  checkScenario,
  classify,
  crisisPointer,
  flipShare,
  gateFailures,
  JUDGE_VERSION,
  loadConversationDataset,
  overusedPhrases,
  percentile,
  runScenario,
  templatedOpener,
  type ConversationScenario,
  type EvaluatedTurn,
} from "./companion-conversation-eval.js";

// Real-provider spend: requires owner approval and a request cap per run (ADR 0049 rollout gate).
if (process.env.COMPANION_EVAL_REAL_PROVIDER !== "approved")
  throw new Error(
    "Set COMPANION_EVAL_REAL_PROVIDER=approved after owner approval",
  );
const number = (name: string, fallback: number) => {
  const value = Number(process.env[name] ?? fallback);
  if (!Number.isFinite(value) || value < 0)
    throw new Error(`${name} must be a non-negative number`);
  return value;
};
const maxRequests = Number(process.env.COMPANION_EVAL_MAX_REQUESTS);
const repeats = Math.max(1, Math.trunc(number("COMPANION_EVAL_REPEATS", 3)));
const delayMs = number("COMPANION_EVAL_DELAY_MS", 4000);
// Sonnet 4.6 list price per million tokens; override with the AWS Bedrock price for the region.
const inputUsd = number("COMPANION_EVAL_INPUT_USD_PER_MTOK", 3);
const outputUsd = number("COMPANION_EVAL_OUTPUT_USD_PER_MTOK", 15);
const datasetPath =
  process.env.COMPANION_EVAL_DATASET ??
  "benchmarks/datasets/companion-conversation-synthetic-v2.json";
const dataset = await loadConversationDataset(datasetPath);
const only = process.env.COMPANION_EVAL_SCENARIOS?.split(",").filter(Boolean);
const scenarios = only
  ? dataset.scenarios.filter((scenario) => only.includes(scenario.id))
  : dataset.scenarios;
if (only && scenarios.length !== only.length)
  throw new Error("COMPANION_EVAL_SCENARIOS names an unknown scenario");
const repeated = (scenario: ConversationScenario) =>
  scenario.category === "crisis" || scenario.category === "hard-negative";
const route = {
  provider: (process.env.COMPANION_EVAL_PROVIDER ??
    "BEDROCK") as ChatRoute["provider"],
  model:
    process.env.COMPANION_EVAL_MODEL ?? "global.anthropic.claude-sonnet-4-6",
};
// A real provider bills: it must run under a positive USD cap. The deterministic fake bills nothing.
const realSpend = route.provider !== "DETERMINISTIC_FAKE";
const maxUsd = realSpend
  ? number("COMPANION_EVAL_MAX_USD", 0)
  : Number.POSITIVE_INFINITY;
if (maxUsd <= 0)
  throw new Error("Set COMPANION_EVAL_MAX_USD to the owner-approved spend cap");
const judging =
  process.env.COMPANION_EVAL_JUDGE !== "off" && route.provider === "BEDROCK";
const judged = (scenario: ConversationScenario) =>
  judging && scenario.turns.some((turn) => turn.phase);
const turnCount = (list: ConversationScenario[]) =>
  list.reduce((sum, scenario) => sum + scenario.turns.length, 0);
const planned =
  turnCount(scenarios) +
  (repeats - 1) * turnCount(scenarios.filter(repeated)) +
  scenarios.filter(judged).length;
const estimate =
  (planned *
    (number("COMPANION_EVAL_EST_INPUT_TOKENS", 5000) * inputUsd +
      number("COMPANION_EVAL_EST_OUTPUT_TOKENS", 400) * outputUsd)) /
  1e6;
process.stdout.write(
  `${JSON.stringify({ plannedRequests: planned, estimatedUsd: Number(estimate.toFixed(2)), maxRequests, maxUsd })}\n`,
);
if (!Number.isInteger(maxRequests) || planned > maxRequests)
  throw new Error(
    `COMPANION_EVAL_MAX_REQUESTS must allow the ${String(planned)} planned requests`,
  );

class CapReached extends Error {}
const usage = {
  companion: { requests: 0, inputTokens: 0, outputTokens: 0 },
  judge: { requests: 0, inputTokens: 0, outputTokens: 0 },
};
const spentUsd = () =>
  ((usage.companion.inputTokens + usage.judge.inputTokens) * inputUsd +
    (usage.companion.outputTokens + usage.judge.outputTokens) * outputUsd) /
  1e6;
const judgeMaxOutputTokens = 800;
let largestInput = number("COMPANION_EVAL_EST_INPUT_TOKENS", 5000);
/** Paces, then refuses a request whose worst case (largest input seen, full output cap) would pass either cap. */
const admit = async (
  counter: { requests: number },
  maxOutputTokens: number,
) => {
  await setTimeout(delayMs);
  const refusal = capRefusal({
    requests: usage.companion.requests + usage.judge.requests,
    maxRequests,
    spentUsd: spentUsd(),
    reserveUsd: (largestInput * inputUsd + maxOutputTokens * outputUsd) / 1e6,
    maxUsd,
  });
  if (refusal) throw new CapReached(refusal);
  counter.requests += 1;
};

const configuration = loadConfiguration();
const routed = new RoutedChatProvider(configuration);
const latencies: number[] = [];
const provider: ChatProvider = {
  reply: async (message, context, chatRoute, options) => {
    await admit(usage.companion, companionMaxOutputTokens);
    const started = performance.now();
    const seen = { usage: false };
    try {
      const reply = await routed.reply(message, context, chatRoute, {
        ...options,
        onUsage: (input, output) => {
          seen.usage = true;
          largestInput = Math.max(largestInput, input);
          usage.companion.inputTokens += input;
          usage.companion.outputTokens += output;
        },
      });
      // Fail closed: spend that cannot be counted cannot be capped.
      if (!seen.usage && realSpend)
        throw new CapReached("provider usage unavailable");
      return reply;
    } finally {
      latencies.push(performance.now() - started);
    }
  },
};
const judge = async (
  scenario: ConversationScenario,
  turns: EvaluatedTurn[],
) => {
  await admit(usage.judge, judgeMaxOutputTokens);
  const result = await callJudge(
    {
      url: bedrockConverseUrl(configuration.BEDROCK_REGION, route.model),
      apiKey: configuration.BEDROCK_API_KEY ?? "",
      maxOutputTokens: judgeMaxOutputTokens,
    },
    scenario,
    turns,
  );
  largestInput = Math.max(largestInput, result.inputTokens);
  usage.judge.inputTokens += result.inputTokens;
  usage.judge.outputTokens += result.outputTokens;
  return result.scores;
};

const catalog = await new ResourceCatalog(configuration).items();
const startedAt = new Date().toISOString();
process.stdout.write(
  `${JSON.stringify({ dataset: dataset.version, route, catalogSize: catalog.length, delayMs, repeats, judging })}\n`,
);
if (catalog.length === 0)
  process.stdout.write(
    "WARNING: empty resource catalogue; RESOURCE suggestions cannot resolve.\n",
  );

interface ScenarioRun {
  scenario: ConversationScenario;
  repeat: number;
  turns: EvaluatedTurn[];
  failures: string[];
}
const runs: ScenarioRun[] = [];
const judgeScores: {
  scenario: string;
  turn: number;
  phase: string;
  scoreA: number;
  scoreB: number;
}[] = [];
let judgeInvalid = 0;
let errors = 0;
let stoppedBy: string | null = null;
let consecutiveErrors = 0;
// One line per scenario as it finishes, so an aborted run keeps what it already paid for.
run: for (let repeat = 1; repeat <= repeats; repeat += 1)
  for (const scenario of scenarios) {
    if (repeat > 1 && !repeated(scenario)) continue;
    try {
      const turns = await runScenario(scenario, provider, route, catalog);
      const failures = checkScenario(
        turns,
        catalog.map((item) => item.title),
      );
      runs.push({ scenario, repeat, turns, failures });
      consecutiveErrors = 0;
      process.stdout.write(
        `${JSON.stringify({ id: scenario.id, repeat, failures, turns })}\n`,
      );
      if (repeat === 1 && judged(scenario)) {
        const scores = await judge(scenario, turns);
        if (scores)
          judgeScores.push(
            ...scores.map((score) => ({ scenario: scenario.id, ...score })),
          );
        else judgeInvalid += 1;
        process.stdout.write(
          `${JSON.stringify({ id: scenario.id, judge: scores })}\n`,
        );
      }
    } catch (error) {
      if (error instanceof CapReached) {
        stoppedBy = error.message;
        break run;
      }
      errors += 1;
      consecutiveErrors += 1;
      process.stdout.write(
        `${JSON.stringify({ id: scenario.id, repeat, error: error instanceof Error ? error.message : "unknown error", code: (error as { code?: unknown }).code })}\n`,
      );
      if (consecutiveErrors >= 3) {
        stoppedBy = "three consecutive provider errors";
        break run;
      }
    }
  }

const firstPass = runs.filter((value) => value.repeat === 1);
const full = classify(firstPass.flatMap((value) => value.turns));
const repeatRuns = Array.from({ length: repeats }, (_, index) =>
  runs.filter(
    (value) => value.repeat === index + 1 && repeated(value.scenario),
  ),
);
const crisisRepeats = repeatRuns.map(
  (list) => classify(list.flatMap((value) => value.turns)).crisis,
);
const predictionsPerTurn = scenarios.filter(repeated).flatMap((scenario) =>
  scenario.turns.flatMap((_, index) => {
    const predictions = repeatRuns.flatMap((list) => {
      const turn = list.find((value) => value.scenario.id === scenario.id)
        ?.turns[index];
      return turn ? [crisisPointer(turn)] : [];
    });
    return predictions.length === repeats ? [predictions] : [];
  }),
);
const failureCounts: Record<string, number> = {};
for (const value of runs)
  for (const code of value.failures)
    failureCounts[code] = (failureCounts[code] ?? 0) + 1;
const arc = arcPassRates(firstPass.map((value) => value.turns));
const gates = [
  ...gateFailures({
    arc,
    crisisFull: full.crisis,
    crisisRepeats,
    failureCounts,
    errors,
  }),
  ...(stoppedBy ? ["INCOMPLETE"] : []),
];
const mean = (values: number[]) =>
  values.length === 0
    ? null
    : values.reduce((sum, value) => sum + value, 0) / values.length;
const metric = (values: (number | null)[]) => {
  const known = values.filter((value): value is number => value !== null);
  return { mean: mean(known), worst: known.length ? Math.min(...known) : null };
};
const phaseMean = (phase: string) => {
  const scores = judgeScores.filter((score) => score.phase === phase);
  return {
    scoreA: mean(scores.map((score) => score.scoreA)),
    scoreB: mean(scores.map((score) => score.scoreB)),
    turns: scores.length,
  };
};
const companionPrompt = companionGuidanceSystem({
  channel: "TEXT",
  friend: true,
});
const sha256 = (value: string) =>
  createHash("sha256").update(value).digest("hex");
const result = {
  story: "MB-845",
  startedAt,
  finishedAt: new Date().toISOString(),
  dataset: {
    version: dataset.version,
    sha256: sha256(await readFile(datasetPath, "utf8")),
    scenarios: scenarios.map((scenario) => scenario.id).length,
    filtered: only !== undefined,
  },
  route,
  guidance: {
    version: COMPANION_GUIDANCE_V2_VERSION,
    friendPromptSha256: sha256(companionPrompt),
  },
  settings: {
    repeats,
    delayMs,
    judge: judging ? JUDGE_VERSION : null,
    catalogSize: catalog.length,
  },
  usage: {
    ...usage,
    pricingUsdPerMillion: { input: inputUsd, output: outputUsd },
    estimatedUsd: Number(spentUsd().toFixed(4)),
    usdPerCompanionRequest:
      usage.companion.requests === 0
        ? null
        : Number(
            (
              (usage.companion.inputTokens * inputUsd +
                usage.companion.outputTokens * outputUsd) /
              1e6 /
              usage.companion.requests
            ).toFixed(5),
          ),
  },
  latencyMs: {
    p50: percentile(latencies, 50),
    p95: percentile(latencies, 95),
    max: latencies.length ? Math.max(...latencies) : null,
    over10s: latencies.filter((value) => value > 10_000).length,
    target: 10_000,
  },
  errors,
  stoppedBy,
  checks: {
    scenarioRuns: runs.length,
    scenarioPassRate: runs.length
      ? runs.filter((value) => value.failures.length === 0).length / runs.length
      : null,
    failureCounts,
    arcPassRates: { ...arc, gates: ARC_PASS_RATE_GATES },
  },
  crisis: {
    firstPass: full.crisis,
    repeats: crisisRepeats,
    recall: metric(crisisRepeats.map((matrix) => matrix.recall)),
    precision: metric(crisisRepeats.map((matrix) => matrix.precision)),
    flipShare: flipShare(predictionsPerTurn),
  },
  suggestion: {
    firstPass: full.suggestion,
    destinationAccuracy: full.destinationAccuracy,
  },
  judge: {
    explore: phaseMean("explore"),
    wrapUp: phaseMean("wrapUp"),
    invalid: judgeInvalid,
    scores: judgeScores,
    note: "Same model as the companion: scores carry same-model bias and do not gate.",
  },
  gates: { failures: gates, passed: gates.length === 0 },
  // Predictions only; reply text stays in the local stdout log.
  turns: runs.flatMap((value) =>
    value.turns.map((turn, index) => ({
      scenario: value.scenario.id,
      repeat: value.repeat,
      turn: index + 1,
      crisis: turn.crisis === true,
      expectSuggestion: turn.expectSuggestion,
      phase: turn.phase ?? null,
      crisisPointer: crisisPointer(turn),
      suggestion: turn.suggestion?.destination ?? null,
    })),
  ),
};
if (process.env.COMPANION_EVAL_RESULT)
  await writeFile(
    process.env.COMPANION_EVAL_RESULT,
    `${JSON.stringify(result, null, 2)}\n`,
  );
const replies = firstPass.map((value) => value.turns.map((turn) => turn.reply));
// Phrases that recur across conversations read as a template even when each conversation passes.
process.stdout.write(
  `${JSON.stringify({ overusedPhrases: overusedPhrases(replies, 3), templatedOpeners: `${String(replies.flat().filter(templatedOpener).length)}/${String(replies.flat().length)}` })}\n`,
);

process.stdout.write(`${JSON.stringify({ ...result, turns: undefined })}\n`);
process.stdout.write(
  [
    "",
    "Human rubric, 1-5 per scenario:",
    "| Scenario | Warmth | Sounds like a friend | Helpful idea | App relevance | Not pushy |",
    "| --- | --- | --- | --- | --- | --- |",
    ...scenarios.map((scenario) => `| ${scenario.id} | | | | | |`),
    "",
  ].join("\n"),
);
if (gates.length > 0) process.exitCode = 1;
