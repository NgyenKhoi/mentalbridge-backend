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

export const conversationMemory = (
  messages: readonly ChatMessage[],
  sources: ConversationContextSources,
  decrypt: (message: ChatMessage) => string,
): { history: HistoryTurn[]; kinds: ContextKind[] } => {
  const history: HistoryTurn[] = [];
  const kinds = new Set<ContextKind>();
  let bytes = 0;
  const recent = messages.slice(-HISTORY_MAX_MESSAGES);
  for (let index = recent.length - 1; index > 0; index -= 1) {
    const assistant = recent[index];
    const user = recent[index - 1];
    if (user?.role !== "USER" || assistant?.role !== "ASSISTANT") continue;
    index -= 1;
    const pairKinds = [...user.contextKinds, ...assistant.contextKinds];
    if (!pairKinds.every((kind) => selected(kind, sources))) continue;
    const userText = decrypt(user);
    const assistantText = decrypt(assistant);
    const pairBytes =
      Buffer.byteLength(userText, "utf8") +
      Buffer.byteLength(assistantText, "utf8");
    if (bytes + pairBytes > HISTORY_MAX_BYTES) break;
    bytes += pairBytes;
    history.unshift(
      { role: "USER", text: userText },
      { role: "ASSISTANT", text: assistantText },
    );
    for (const kind of pairKinds) kinds.add(kind);
  }
  return { history, kinds: [...kinds] };
};

export const recentHistory = (
  messages: readonly ChatMessage[],
  sources: ConversationContextSources,
  decrypt: (message: ChatMessage) => string,
): HistoryTurn[] => conversationMemory(messages, sources, decrypt).history;
