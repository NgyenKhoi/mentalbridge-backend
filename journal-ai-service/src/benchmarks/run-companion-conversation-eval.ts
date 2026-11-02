import { loadConfiguration } from "../configuration/configuration.js";
import { RoutedChatProvider } from "../companion-chat/companion-chat.js";
import { ResourceCatalog } from "../companion-chat/resource-catalog.js";
import {
  checkScenario,
  loadConversationDataset,
  runScenario,
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
const provider = new RoutedChatProvider(configuration);
const route = {
  provider: (process.env.COMPANION_EVAL_PROVIDER ?? "BEDROCK") as "BEDROCK",
  model:
    process.env.COMPANION_EVAL_MODEL ?? "global.anthropic.claude-sonnet-4-6",
};
const catalog = await new ResourceCatalog(configuration).items();
const results = [];
for (const scenario of dataset.scenarios) {
  const turns = await runScenario(scenario, provider, route, catalog);
  results.push({
    id: scenario.id,
    failures: checkScenario(turns),
    turns: turns.map((turn, index) => ({
      user: scenario.turns[index]?.user,
      ...turn,
    })),
  });
}

process.stdout.write(
  `${JSON.stringify({ dataset: dataset.version, route, catalogSize: catalog.length, results }, null, 2)}\n`,
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
