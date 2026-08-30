import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const baseline = await readFile(
  new URL('../migrations/1_initial_schema.sql', import.meta.url),
  'utf8',
);
const hotlineRemoval = await readFile(
  new URL('../migrations/2_remove_hotline_catalogue.sql', import.meta.url),
  'utf8',
);

assert.match(baseline, /^-- Up Migration/m);

for (const table of ['resource', 'notification_preference', 'notification']) {
  assert.match(baseline, new RegExp(`CREATE TABLE IF NOT EXISTS ${table}\\b`));
}

assert.match(baseline, /CREATE TABLE IF NOT EXISTS hotline\b/);
assert.match(hotlineRemoval, /^-- Up Migration/m);
assert.match(hotlineRemoval, /DROP TABLE IF EXISTS hotline\b/);
assert.doesNotMatch(baseline, /CREATE DATABASE|CREATE SCHEMA/i);
assert.doesNotMatch(hotlineRemoval, /CREATE DATABASE|CREATE SCHEMA/i);
console.log('Validated node-pg-migrate migrations: baseline plus hotline catalogue removal');
