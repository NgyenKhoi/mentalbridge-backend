import { fileURLToPath } from "node:url";
import {
  corpus,
  devices,
  noises,
  regions,
  sttModels,
  ttsModel,
  denoiseModes,
} from "./corpus.js";
import { aggregate } from "./report.js";
import { BenchmarkFailure } from "./metrics.js";
export async function main(
  args: string[],
  input: AsyncIterable<Uint8Array | string> | Iterable<Uint8Array | string>,
  output: (text: string) => void,
) {
  if (args.length === 1 && args[0] === "plan") {
    output(
      JSON.stringify({
        task: "MB-760",
        providerCalls: 0,
        status: "NOT_RUN",
        scripts: corpus,
        sttModels,
        ttsModel,
        devices,
        noises,
        regions,
        denoiseModes,
        latencyWarning:
          "provider first frame is not device-audible or Journal/Bedrock end-to-end latency",
      }),
    );
    return;
  }
  if (args.length !== 1 || args[0] !== "score")
    throw new BenchmarkFailure("INVALID_INPUT");
  let bytes = 0;
  const chunks = [];
  for await (const value of input) {
    const chunk = Buffer.from(value);
    bytes += chunk.length;
    if (bytes > 4000000) throw new BenchmarkFailure("INVALID_INPUT");
    chunks.push(chunk);
  }
  let data: unknown;
  try {
    data = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw new BenchmarkFailure("INVALID_INPUT");
  }
  output(JSON.stringify(aggregate(data)));
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2), process.stdin, (text) => {
    process.stdout.write(text + "\n");
  }).catch(() => {
    process.stderr.write("MB760_INVALID_INPUT\n");
    process.exitCode = 1;
  });
}
