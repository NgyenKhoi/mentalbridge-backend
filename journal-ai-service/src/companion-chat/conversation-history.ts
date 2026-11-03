import type {
  ChatMessage,
  ContextKind,
  ConversationContextSources,
} from "./companion-chat.js";

export interface HistoryTurn {
  role: "USER" | "ASSISTANT";
  text: string;
}

const HISTORY_MAX_MESSAGES = 8;
const HISTORY_MAX_BYTES = 3_000;

const selected = (kind: ContextKind, sources: ConversationContextSources) =>
  kind === "JOURNAL"
    ? sources.diary
    : kind === "SUPPORT_PLAN"
      ? sources.plan
      : kind === "REASSESSMENT"
        ? sources.screening
        : sources.resourceIds.length > 0;

/** Recent turns of one conversation, oldest first; turns grounded in withdrawn context are left out. */
export const recentHistory = (
  messages: readonly ChatMessage[],
  sources: ConversationContextSources,
  decrypt: (message: ChatMessage) => string,
): HistoryTurn[] => {
  const history: HistoryTurn[] = [];
  let bytes = 0;
  for (const message of messages.slice(-HISTORY_MAX_MESSAGES).reverse()) {
    if (!message.contextKinds.every((kind) => selected(kind, sources)))
      continue;
    const text = decrypt(message);
    bytes += Buffer.byteLength(text, "utf8");
    if (bytes > HISTORY_MAX_BYTES) break;
    history.unshift({ role: message.role, text });
  }
  return history;
};
