export const normalizeSignal = (value: unknown): unknown => {
  if (typeof value !== "string") return value;
  return value.trim().slice(0, 64).trim();
};

export const normalizeSignals = (value: unknown, limit = 12): unknown => {
  if (!Array.isArray(value)) return value;
  return value
    .map(normalizeSignal)
    .filter((signal) => signal !== "")
    .slice(0, limit);
};
