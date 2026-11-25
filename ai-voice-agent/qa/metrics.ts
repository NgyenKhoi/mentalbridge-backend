export class BenchmarkFailure extends Error {
  constructor(
    readonly code:
      | "INVALID_INPUT"
      | "BUDGET_EXCEEDED"
      | "LIVE_BLOCKED"
      | "PROVIDER_FAILED",
  ) {
    super(code);
  }
}
export interface EditCounts {
  referenceUnits: number;
  substitutions: number;
  deletions: number;
  insertions: number;
  rate: number | null;
}
export const normalize = (text: string) =>
  text
    .normalize("NFC")
    .toLocaleLowerCase("vi")
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim()
    .replace(/\s+/gu, " ");
export function edits(
  reference: readonly string[],
  hypothesis: readonly string[],
): EditCounts {
  if (reference.length > 2000 || hypothesis.length > 2000)
    throw new BenchmarkFailure("INVALID_INPUT");
  let previous = Array.from(
    { length: hypothesis.length + 1 },
    (_, insertions) => ({ substitutions: 0, deletions: 0, insertions }),
  );
  const total = (value: (typeof previous)[number]) =>
    value.substitutions + value.deletions + value.insertions;
  for (let i = 1; i <= reference.length; i++) {
    const row = [{ substitutions: 0, deletions: i, insertions: 0 }];
    for (let j = 1; j <= hypothesis.length; j++) {
      const diagonal = previous[j - 1];
      const above = previous[j];
      const left = row[j - 1];
      if (!diagonal || !above || !left)
        throw new BenchmarkFailure("INVALID_INPUT");
      if (reference[i - 1] === hypothesis[j - 1]) row.push({ ...diagonal });
      else
        row.push(
          [
            { ...diagonal, substitutions: diagonal.substitutions + 1 },
            { ...above, deletions: above.deletions + 1 },
            { ...left, insertions: left.insertions + 1 },
          ].sort((a, b) => total(a) - total(b))[0] ?? diagonal,
        );
    }
    previous = row;
  }
  const result = previous[hypothesis.length];
  if (!result) throw new BenchmarkFailure("INVALID_INPUT");
  return {
    ...result,
    referenceUnits: reference.length,
    rate: reference.length ? total(result) / reference.length : null,
  };
}
export function score(reference: string, hypothesis: string) {
  if (reference.length > 2000 || hypothesis.length > 2000)
    throw new BenchmarkFailure("INVALID_INPUT");
  const a = normalize(reference);
  const b = normalize(hypothesis);
  const graphemes = (value: string) =>
    Array.from(
      new Intl.Segmenter("vi", { granularity: "grapheme" }).segment(
        value.replaceAll(" ", ""),
      ),
      (item) => item.segment,
    );
  return {
    wer: edits(a ? a.split(" ") : [], b ? b.split(" ") : []),
    cer: edits(graphemes(a), graphemes(b)),
  };
}
export function percentiles(values: readonly number[]) {
  if (
    values.some(
      (value) => !Number.isFinite(value) || value < 0 || value > 300000,
    )
  )
    throw new BenchmarkFailure("INVALID_INPUT");
  const sorted = [...values].sort((a, b) => a - b);
  const at = (quantile: number) =>
    sorted[Math.ceil(quantile * sorted.length) - 1] ?? null;
  return { samples: values.length, p50Ms: at(0.5), p95Ms: at(0.95) };
}
