import { z } from "zod";
import type { ServiceConfiguration } from "../configuration/configuration.js";

export interface CatalogItem {
  ref: string;
  resourceId: string;
  category: string;
  title: string;
}

const CACHE_MS = 10 * 60_000;
const PROMPT_MAX_BYTES = 1_500;

const listSchema = z.looseObject({
  data: z.array(z.unknown()).max(100),
  fallback: z.string().optional(),
});
const itemSchema = z.looseObject({
  id: z.uuid(),
  category: z.string().min(1).max(40),
  title: z.string().trim().min(1).max(200),
});

// shortcut: first 30 published items, not relevance-ranked; upgrade to category- or keyword-filtered when the catalogue grows past ~30.
/** Published reviewed Resources the TEXT companion may offer, cached in memory. */
export class ResourceCatalog {
  private cached: { items: CatalogItem[]; expiresAt: number } | null = null;

  constructor(
    private readonly configuration: ServiceConfiguration,
    private readonly now: () => number = Date.now,
  ) {}

  async items(): Promise<CatalogItem[]> {
    if (this.cached && this.now() < this.cached.expiresAt)
      return this.cached.items;
    const url = new URL(
      "/api/v1/resources",
      this.configuration.CONTENT_BASE_URL,
    );
    url.searchParams.set("locale", "vi-VN");
    url.searchParams.set("limit", "30");
    let body: unknown;
    try {
      const response = await fetch(url, {
        signal: AbortSignal.timeout(this.configuration.CONTENT_TIMEOUT_MS),
      });
      if (!response.ok) return [];
      body = await response.json();
    } catch {
      return [];
    }
    const list = listSchema.safeParse(body);
    if (!list.success || list.data.fallback !== undefined) return [];
    const items = list.data.data
      .flatMap((value) => {
        const item = itemSchema.safeParse(value);
        return item.success ? [item.data] : [];
      })
      .map((item, index) => ({
        ref: `R${String(index + 1)}`,
        resourceId: item.id,
        category: item.category,
        title: item.title,
      }));
    this.cached = { items, expiresAt: this.now() + CACHE_MS };
    return items;
  }
}

/** Compact catalogue for the provider: refs only, never ids, summaries or links. */
export const catalogPrompt = (items: readonly CatalogItem[]) => {
  const prompt: { ref: string; category: string; title: string }[] = [];
  for (const { ref, category, title } of items) {
    prompt.push({ ref, category, title });
    if (Buffer.byteLength(JSON.stringify(prompt)) > PROMPT_MAX_BYTES) {
      prompt.pop();
      break;
    }
  }
  return prompt;
};
