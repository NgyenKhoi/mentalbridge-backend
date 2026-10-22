import assert from 'node:assert/strict';
import { test } from 'node:test';
import { missingOriginalLines } from './verify-documentation-preservation.mjs';

test('a missing occurrence cannot be masked by another identical field row', () => {
  const row = '| `version` | Optimistic version. |';
  const missing = missingOriginalLines(`${row}\n${row}`, 'docs/original.md', [
    { file: 'docs/owner.md', content: row },
  ]);
  assert.deepEqual(missing, [row]);
});

test('moved links preserve their original destination including spaced paths and fragments', () => {
  const original = '[Policy](policies/rules.md#current)\r\n[Source](<../Source (1).docx>)\r\n';
  const destinations = [{
    file: 'docs/owners/care.md',
    content: '[Policy](../policies/rules.md#current)\n[Source](<../../Source (1).docx>)\n',
  }];
  assert.deepEqual(missingOriginalLines(original, 'docs/original.md', destinations), []);
});

test('changed link destinations or factual text fail preservation', () => {
  const source = 'docs/original.md';
  const original = '[Policy](policies/rules.md#current)\nOwner is Care.\n';
  const missing = missingOriginalLines(original, source, [{
    file: 'docs/owners/care.md',
    content: '[Policy](../policies/rules.md#historical)\nOwner is Content.\n',
  }]);
  assert.equal(missing.length, 2);
});
