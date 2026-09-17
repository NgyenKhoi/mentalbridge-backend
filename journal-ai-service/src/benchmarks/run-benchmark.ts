import { BenchmarkRunner } from "./benchmark.js";
import { loadConfiguration } from "../configuration/configuration.js";

const configuration = loadConfiguration();
const result = await new BenchmarkRunner(configuration).run();

process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
