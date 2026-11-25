import type { CatalogItem } from "./resource-catalog.js";

export const SUGGESTION_DESTINATIONS = [
  "RESOURCE",
  "RESOURCE_LIBRARY",
  "JOURNAL",
  "EMOTION_CHECK_IN",
  "SUPPORT_PLAN",
  "SPECIALIST_DISCOVERY",
] as const;

export type SuggestionDestination = (typeof SUGGESTION_DESTINATIONS)[number];

/** What the model returns: a closed destination and, for RESOURCE, a catalogue ref. */
export interface ProviderSuggestion {
  destination: string;
  resourceRef: string | null;
}

/** What the server resolved; the web owns hrefs and labels. */
export interface CompanionSuggestion {
  destination: SuggestionDestination;
  resourceId: string | null;
  resourceTitle: string | null;
}

const destination = (value: string): value is SuggestionDestination =>
  (SUGGESTION_DESTINATIONS as readonly string[]).includes(value);

/** Invalid suggestions become null so the reply itself still succeeds. */
export const resolveSuggestion = (
  raw: ProviderSuggestion | null | undefined,
  catalog: readonly CatalogItem[],
): CompanionSuggestion | null => {
  if (!raw || !destination(raw.destination)) return null;
  if (raw.destination !== "RESOURCE")
    return raw.resourceRef === null
      ? { destination: raw.destination, resourceId: null, resourceTitle: null }
      : null;
  const item = catalog.find((value) => value.ref === raw.resourceRef);
  return item
    ? {
        destination: "RESOURCE",
        resourceId: item.resourceId,
        resourceTitle: item.title,
      }
    : null;
};
