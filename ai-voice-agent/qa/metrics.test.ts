import assert from "node:assert/strict";
import test from "node:test";
import { score, edits, percentiles, BenchmarkFailure } from "./metrics.js";
void test("Vietnamese NFC, case and punctuation normalize consistently without deleting diacritics", () => {
  assert.equal(score("Mình căng thẳng!", "MÌNH CĂNG THẲNG").wer.rate, 0);
  assert.ok((score("không", "khong").cer.rate ?? 0) > 0);
});
void test("WER reports exact substitutions/deletions/insertions and never clamps insertion-heavy errors", () => {
  assert.deepEqual(edits(["a", "b", "c"], ["a", "x", "c"]), {
    referenceUnits: 3,
    substitutions: 1,
    deletions: 0,
    insertions: 0,
    rate: 1 / 3,
  });
  assert.equal(edits(["a", "b"], ["a"]).deletions, 1);
  assert.equal(edits(["a"], ["a", "b", "c"]).rate, 2);
  assert.equal(edits([], ["noise"]).rate, null);
  assert.equal(edits([], ["noise"]).insertions, 1);
});
void test("CER counts Unicode graphemes after NFC, not UTF-16 or whitespace", () => {
  assert.equal(score("Để mình", "để mình").cer.referenceUnits, 6);
  assert.equal(score("a b", "ab").cer.rate, 0);
  assert.equal(score("", "").wer.rate, null);
});
void test("latency nearest-rank percentiles preserve missing samples instead of inventing zero", () => {
  assert.deepEqual(percentiles([]), { samples: 0, p50Ms: null, p95Ms: null });
  assert.deepEqual(percentiles([100, 10, 20, 40]), {
    samples: 4,
    p50Ms: 20,
    p95Ms: 100,
  });
  assert.throws(() => percentiles([NaN]), BenchmarkFailure);
  assert.throws(() => percentiles([-1]), BenchmarkFailure);
  assert.throws(() => score("x".repeat(2001), ""), BenchmarkFailure);
});
