import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
const root = new URL("../", import.meta.url);
const manifest = JSON.parse(
  await readFile(new URL("package.json", root), "utf8"),
);
assert.equal(manifest.private, true);
assert.ok(
  !Object.keys(manifest.dependencies).some((name) =>
    /bedrock|anthropic|openai|mongodb|redis|kafka/iu.test(name),
  ),
);
for (const file of await readdir(new URL("src/", root))) {
  if (!file.endsWith(".ts") || file.endsWith(".test.ts")) continue;
  const source = await readFile(new URL(`src/${file}`, root), "utf8");
  assert.ok(
    !/\b(?:writeFile|appendFile|createWriteStream)\s*\(/u.test(source),
    `${file}: no transcript/filesystem output`,
  );
  assert.ok(
    !/record\s*:\s*true|transcriptionEnabled\s*:\s*true/u.test(source),
    `${file}: no content observability`,
  );
}
console.log(
  "No owner database, direct reasoning provider, persistence or recording boundary introduced; no migration needed.",
);
