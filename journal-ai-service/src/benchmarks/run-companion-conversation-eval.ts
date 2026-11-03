import { setTimeout } from "node:timers/promises";
import { loadConfiguration } from "../configuration/configuration.js";
import {
  RoutedChatProvider,
  type ChatProvider,
} from "../companion-chat/companion-chat.js";
import { ResourceCatalog } from "../companion-chat/resource-catalog.js";
import {
  checkScenario,
  loadConversationDataset,
  overusedPhrases,
  runScenario,
  templatedOpener,
} from "./companion-conversation-eval.js";

// Real-provider spend: requires owner approval and a request cap per run (ADR 0049 rollout gate).
if (process.env.COMPANION_EVAL_REAL_PROVIDER !== "approved")
  throw new Error(
    "Set COMPANION_EVAL_REAL_PROVIDER=approved after owner approval",
  );
const maxRequests = Number(process.env.COMPANION_EVAL_MAX_REQUESTS);
const dataset = await loadConversationDataset();
const requests = dataset.scenarios.reduce(
  (sum, scenario) => sum + scenario.turns.length,
  0,
);
if (!Number.isInteger(maxRequests) || requests > maxRequests)
  throw new Error(
    `COMPANION_EVAL_MAX_REQUESTS must allow the ${String(requests)} requests of this dataset`,
  );

const configuration = loadConfiguration();
const routed = new RoutedChatProvider(configuration);
// Pace requests so provider rate limits (HTTP 429) do not abort the run.
const delayMs = Number(process.env.COMPANION_EVAL_DELAY_MS ?? "4000");
const provider: ChatProvider = {
  reply: async (...args) => {
    await setTimeout(delayMs);
    return routed.reply(...args);
  },
};
const route = {
  provider: (process.env.COMPANION_EVAL_PROVIDER ?? "BEDROCK") as "BEDROCK",
  model:
    process.env.COMPANION_EVAL_MODEL ?? "global.anthropic.claude-sonnet-4-6",
};
const catalog = await new ResourceCatalog(configuration).items();
process.stdout.write(
  `${JSON.stringify({ dataset: dataset.version, route, catalogSize: catalog.length, delayMs })}\n`,
);
// One line per scenario as it finishes, so an aborted run keeps what it already paid for.
const replies: string[][] = [];
for (const scenario of dataset.scenarios) {
  try {
    const turns = await runScenario(scenario, provider, route, catalog);
    replies.push(turns.map((turn) => turn.reply));
    process.stdout.write(
      `${JSON.stringify({
        id: scenario.id,
        failures: checkScenario(
          turns,
          catalog.map((item) => item.title),
        ),
        turns: turns.map((turn, index) => ({
          user: scenario.turns[index]?.user,
          ...turn,
        })),
      })}\n`,
    );
  } catch (error) {
    process.stdout.write(
      `${JSON.stringify({ id: scenario.id, aborted: error instanceof Error ? error.message : "unknown error", code: (error as { code?: unknown }).code })}\n`,
    );
    process.exitCode = 1;
    break;
  }
}
// Phrases that recur across conversations read as a template even when each conversation passes.
process.stdout.write(
  `${JSON.stringify({ overusedPhrases: overusedPhrases(replies, 3), templatedOpeners: `${String(replies.flat().filter(templatedOpener).length)}/${String(replies.flat().length)}` })}
`,
);
process.stdout.write(
  [
    "",
    "Human rubric, 1-5 per scenario:",
    "| Scenario | Warmth | Sounds like a friend | Helpful idea | App relevance | Not pushy |",
    "| --- | --- | --- | --- | --- | --- |",
    ...dataset.scenarios.map((scenario) => `| ${scenario.id} | | | | | |`),
    "",
  ].join("\n"),
);
