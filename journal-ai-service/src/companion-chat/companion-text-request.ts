import type { MinimizedContext } from "./companion-chat.js";
import type { HistoryTurn } from "./conversation-history.js";
import { catalogPrompt, type CatalogItem } from "./resource-catalog.js";

export interface FriendInput {
  history: HistoryTurn[];
  catalog: CatalogItem[];
}

/** The provider's user payload; also what the token estimate measures. Without friend it is the v1 shape. */
export const companionUserInput = (payload: {
  message: string;
  context: MinimizedContext;
  friend?: FriendInput;
}): string =>
  JSON.stringify({
    message: payload.message,
    context: { kinds: payload.context.kinds, data: payload.context.prompt },
    ...(payload.friend
      ? {
          history: payload.friend.history,
          catalog: catalogPrompt(payload.friend.catalog),
        }
      : {}),
  })
    .replaceAll("<", "\\u003c")
    .replaceAll(">", "\\u003e");

export const estimateInputTokens = (system: string, input: string): number =>
  Math.ceil(Buffer.byteLength(system + input, "utf8") / 4);
