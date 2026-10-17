export interface EmotionStreak {
  readonly completedToday: boolean;
  readonly currentStreak: number;
  readonly longestStreak: number;
}

export const localDateOrdinal = (localDate: string): number => {
  const [year, month, day] = localDate.split("-").map(Number);
  if (year === undefined || month === undefined || day === undefined)
    throw new Error("Local date is invalid");
  return Math.floor(Date.UTC(year, month - 1, day) / 86_400_000);
};

export const localDateFromOrdinal = (ordinal: number): string =>
  new Date(ordinal * 86_400_000).toISOString().slice(0, 10);

export const calculateEmotionStreak = (
  dates: readonly string[],
  asOfLocalDate: string,
): EmotionStreak => {
  const asOfOrdinal = localDateOrdinal(asOfLocalDate);
  const activeDateOrdinals = new Set(
    dates
      .filter((localDate) => localDate <= asOfLocalDate)
      .map(localDateOrdinal),
  );

  let currentStreak = 0;
  let streakOrdinal = asOfOrdinal;
  if (!activeDateOrdinals.has(streakOrdinal)) streakOrdinal -= 1;
  for (
    let ordinal = streakOrdinal;
    activeDateOrdinals.has(ordinal);
    ordinal -= 1
  )
    currentStreak += 1;

  let longestStreak = 0;
  let run = 0;
  let previous: number | undefined;
  for (const ordinal of [...activeDateOrdinals].sort(
    (left, right) => left - right,
  )) {
    run = previous !== undefined && ordinal === previous + 1 ? run + 1 : 1;
    longestStreak = Math.max(longestStreak, run);
    previous = ordinal;
  }

  return {
    completedToday: activeDateOrdinals.has(asOfOrdinal),
    currentStreak,
    longestStreak,
  };
};
