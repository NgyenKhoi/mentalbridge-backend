import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const baseline = await readFile(
  new URL('../migrations/1_initial_schema.sql', import.meta.url),
  'utf8',
);

assert.match(baseline, /^-- Up Migration/m);

for (const table of ['resource', 'hotline', 'notification_preference', 'notification']) {
  assert.match(baseline, new RegExp(`CREATE TABLE IF NOT EXISTS ${table}\\b`));
}

assert.doesNotMatch(baseline, /CREATE DATABASE|CREATE SCHEMA/i);
console.log('Validated node-pg-migrate baseline: migrations/1_initial_schema.sql');
