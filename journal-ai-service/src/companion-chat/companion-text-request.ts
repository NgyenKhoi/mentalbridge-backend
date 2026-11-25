import type { MinimizedContext } from "./companion-chat.js";
import type { HistoryTurn } from "./conversation-history.js";
import { catalogPrompt, type CatalogItem } from "./resource-catalog.js";

/** An earlier Mầm conversation note (MB-846c), only with effective L2. */
export interface PreviousConversation {
  date: string;
  title: string;
  note: string;
}

export interface FriendInput {
  history: HistoryTurn[];
  catalog: CatalogItem[];
  previousConversations?: PreviousConversation[];
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
          ...(payload.friend.previousConversations?.length
            ? { previous_conversations: payload.friend.previousConversations }
            : {}),
          history: payload.friend.history,
          catalog: catalogPrompt(payload.friend.catalog),
        }
      : {}),
  })
    .replaceAll("<", "\\u003c")
    .replaceAll(">", "\\u003e");

export const estimateInputTokens = (system: string, input: string): number =>
  Math.ceil(Buffer.byteLength(system + input, "utf8") / 4);
