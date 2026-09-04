import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const migration = require('../migrations/001_realtime_message_foundation.cjs');

for (const collection of ['conversations', 'messages']) {
  assert.ok(migration.validators[collection]);
  assert.equal(migration.validators[collection].$jsonSchema.additionalProperties, false);
}
const messageProperties = migration.validators.messages.$jsonSchema.properties;
assert.ok(messageProperties.bodyCiphertext);
assert.ok(messageProperties.commandFingerprint);
assert.equal(messageProperties.content, undefined);
assert.equal(messageProperties.body, undefined);
assert.equal(typeof migration.up, 'function');
assert.equal(typeof migration.down, 'function');

console.log('Validated Realtime MongoDB migration baseline');
