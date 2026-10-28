import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { z } from "zod";
import {
  controlSchema,
  leaseSchema,
  replySchema,
  reasons,
} from "../dist/contract.js";
const api = JSON.parse(
  await readFile(
    new URL(
      "../../contracts/proposals/companion-voice-v1.openapi.json",
      import.meta.url,
    ),
    "utf8",
  ),
);
for (const [name, schema] of [
  ["AgentLease", leaseSchema],
  ["VoiceReply", replySchema],
]) {
  const actual = z.toJSONSchema(schema);
  const expected = api.components.schemas[name];
  assert.equal(actual.additionalProperties, expected.additionalProperties);
  assert.deepEqual([...actual.required].sort(), [...expected.required].sort());
  assert.deepEqual(
    Object.keys(actual.properties).sort(),
    Object.keys(expected.properties).sort(),
  );
  for (const [key, value] of Object.entries(expected.properties))
    for (const constraint of ["const", "format", "maxLength", "minLength"])
      if (value[constraint] !== undefined)
        assert.equal(
          actual.properties[key][constraint],
          value[constraint],
          `${name}.${key}.${constraint}`,
        );
}
const controls = api.components.schemas.AgentControlResponse;
assert.equal(
  z.toJSONSchema(controlSchema).oneOf?.length ??
    z.toJSONSchema(controlSchema).anyOf?.length,
  controls.oneOf.length,
);
const worker = JSON.parse(
  await readFile(
    new URL(
      "../../contracts/proposals/companion-voice-worker-v1.schema.json",
      import.meta.url,
    ),
    "utf8",
  ),
);
assert.deepEqual(
  reasons,
  worker.$defs.STOP.properties.payload.properties.reason.enum,
);
for (const operation of ["agent-claim", "agent-control", "turns"]) {
  const route =
    api.paths[
      `/internal/v1/ai-companion/voice-sessions/{voiceSessionId}/${operation}`
    ];
  assert.ok(route);
  assert.deepEqual(route.post.security, [{ VoiceWorkerToken: [] }]);
}
console.log(
  "Voice consumer field/bounds/authentication match the frozen MB-755 contract.",
);
