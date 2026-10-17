import { loadConfiguration } from "../configuration/configuration.js";
import { warmBedrockStructuredOutputs } from "./bedrock-warmup.js";

const result = await warmBedrockStructuredOutputs(loadConfiguration());

process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
