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

/** Mầm TEXT v3 window (MB-846b): replies reach 1500 characters, so 8 messages / 3 KB would keep about one pair. */
export const MAM_HISTORY_LIMITS = { messages: 20, bytes: 16_000 } as const;

/** Bounded input for one MB-846c memory note on an earlier conversation. */
export const MAM_NOTE_TRANSCRIPT_LIMITS = {
  messages: 20,
  bytes: 8_000,
} as const;

const selected = (kind: ContextKind, sources: ConversationContextSources) =>
  kind === "JOURNAL"
    ? sources.diary
    : kind === "SUPPORT_PLAN"
      ? sources.plan
      : kind === "REASSESSMENT"
        ? sources.screening
        : sources.resourceIds.length > 0;

/**
 * Roles and readable text of recent messages for the mode selector. A message whose pair used personal
 * context that is no longer selected and authorized keeps its role but is never decrypted (ADR 0050).
 */
export const readableTurns = (
  messages: readonly ChatMessage[],
  sources: ConversationContextSources,
  decrypt: (message: ChatMessage) => string,
): HistoryTurn[] =>
  messages.map((message, index) => {
    const partner =
      message.role === "USER" ? messages[index + 1] : messages[index - 1];
    const pairKinds = [
      ...message.contextKinds,
      ...(partner && partner.role !== message.role ? partner.contextKinds : []),
    ];
    return {
      role: message.role,
      text: pairKinds.every((kind) => selected(kind, sources))
        ? decrypt(message)
        : "(nội dung riêng tư không đọc)",
    };
  });

export const conversationMemory = (
  messages: readonly ChatMessage[],
  sources: ConversationContextSources,
  decrypt: (message: ChatMessage) => string,
  limits: { messages: number; bytes: number } = {
    messages: HISTORY_MAX_MESSAGES,
    bytes: HISTORY_MAX_BYTES,
  },
): { history: HistoryTurn[]; kinds: ContextKind[] } => {
  const history: HistoryTurn[] = [];
  const kinds = new Set<ContextKind>();
  let bytes = 0;
  const recent = messages.slice(-limits.messages);
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
    if (history.length > 0 && bytes + pairBytes > limits.bytes) break;
    bytes += pairBytes;
    history.unshift(
      { role: "USER", text: userText },
      { role: "ASSISTANT", text: assistantText },
    );
    for (const kind of pairKinds) kinds.add(kind);
  }
  const opener = messages[0];
  if (
    opener?.role === "ASSISTANT" &&
    recent[0] === opener &&
    opener.contextKinds.every((kind) => selected(kind, sources))
  ) {
    const openerText = decrypt(opener);
    const openerBytes = Buffer.byteLength(openerText, "utf8");
    if (history.length === 0 || bytes + openerBytes <= limits.bytes) {
      history.unshift({ role: "ASSISTANT", text: openerText });
      for (const kind of opener.contextKinds) kinds.add(kind);
    }
  }
  return { history, kinds: [...kinds] };
};

export const recentHistory = (
  messages: readonly ChatMessage[],
  sources: ConversationContextSources,
  decrypt: (message: ChatMessage) => string,
): HistoryTurn[] => conversationMemory(messages, sources, decrypt).history;
