import type { HistoryTurn } from "./conversation-history.js";

export const RESPONSE_MODES = [
  "LISTEN",
  "CLARIFY",
  "SUPPORT_AND_WRAP_UP",
  "BOUNDARY",
  "SAFETY",
] as const;

export type ResponseMode = (typeof RESPONSE_MODES)[number];

const plain = (value: string): string =>
  value
    .toLowerCase()
    .normalize("NFD")
    .replace(/[̀-ͯ]/gu, "")
    .replaceAll("đ", "d");

// JS \b is ASCII-only, so Vietnamese phrases need letter-aware boundaries.
const phrases = (source: string): RegExp =>
  new RegExp(`(?<![\\p{L}\\p{N}])(?:${source})(?![\\p{L}\\p{N}])`, "u");

const FIRST_PERSON = "mình|tôi|tui|tớ|tao|em";

// Matched on the original lower-cased text, so “từ từ”, “dạy”, “dao làm bếp” never read as risk words.
const CRISIS_ACCENTED = [
  phrases(
    "tự tử|tự sát|tự vẫn|tuyệt mệnh|thư tuyệt mệnh|quá liều|tự kết liễu|kết liễu (?:bản thân|đời mình|cuộc đời)",
  ),
  phrases(
    "tự (?:làm )?(?:hại|đau) (?:bản thân|mình)|tự hại|làm (?:hại|đau) bản thân|tự (?:cắt|rạch|cứa) tay|rạch tay",
  ),
  phrases(
    `(?:${FIRST_PERSON}) (?:đã |lại |vừa |muốn |hay |định )?(?:cắt|cứa) tay(?! áo)|cào tay (?:đến|tới) chảy máu|lấy dao (?:rạch|cắt|cứa)`,
  ),
  phrases(
    "nhảy lầu|nhảy (?:xuống )?từ (?:tầng|sân thượng|nóc)|nhảy xuống (?:sông|cầu|hồ)|uống (?:hết|cả) (?:lọ|vỉ|hộp|nắm) thuốc|uống hết thuốc|gom (?:đủ )?thuốc|tích (?:đủ )?thuốc",
  ),
  phrases(
    "(?:không|chẳng) (?:còn )?(?:muốn|thiết) sống|sống (?:để )?làm gì nữa|(?:không|chẳng) còn lý do (?:gì )?để sống|thà chết|chết (?:đi|quách(?: đi)?) cho (?:xong|rồi|nhẹ)|muốn chết cho xong|chỉ muốn chết|muốn chết đi",
  ),
  phrases(`(?:${FIRST_PERSON}) (?:thật sự |thực sự |rất |cũng )?muốn chết`),
  phrases(
    "(?:không|chẳng) muốn (?:thức|tỉnh) dậy nữa|(?:ngủ|nhắm mắt) (?:một giấc )?(?:mãi mãi|không bao giờ (?:dậy|tỉnh))|không bao giờ tỉnh dậy nữa",
  ),
  phrases(
    `muốn biến mất (?:khỏi (?:thế giới|cuộc đời|cõi đời)|mãi mãi|vĩnh viễn)|bay màu khỏi (?:thế giới|cuộc đời)|(?:nếu|khi|giá mà|ước gì|lỡ) (?:${FIRST_PERSON}) (?:mà )?không còn trên (?:đời|cõi đời|thế giới)`,
  ),
  phrases(
    "nghĩ (?:tới|đến) (?:chuyện |việc )?(?:chết|tự tử)|nghĩ đến cái chết",
  ),
];

// Farewell words are everyday speech; they count only together with a second risk cue.
const FAREWELL = phrases(
  "lần cuối|lời tạm biệt|kết thúc mọi thứ|chấm dứt (?:hết|tất cả)|biến mất|cho hết đồ đạc",
);
const FAREWELL_RISK = phrases(
  `mãi mãi|vĩnh viễn|không ai cần (?:${FIRST_PERSON})|là gánh nặng|coi như xong|(?:chắc )?mọi người (?:sẽ )?nhẹ nhõm|không quay (?:về|lại) nữa|di chúc|thư tuyệt mệnh`,
);

// Only for messages typed without any diacritics; each phrase stays unambiguous once accents are gone.
// “tu tu” is left out on purpose: it is also “từ từ”.
const CRISIS_UNACCENTED = phrases(
  [
    "tu sat",
    "tu ket lieu",
    "ket lieu ban than",
    "tu (?:lam )?hai ban than",
    "lam hai ban than",
    "rach tay",
    "nhay lau",
    "khong (?:con )?(?:muon|thiet) song",
    "chang (?:muon|thiet) song",
    "song lam gi nua",
    "tha chet",
    "chet (?:di|quach) cho (?:xong|roi)",
    "muon chet cho xong",
    "chi muon chet",
    "(?:minh|toi|tui) (?:that su |rat )?muon chet",
    "thu tuyet menh",
    "qua lieu",
    "uong het (?:lo|vi) thuoc",
    "khong muon thuc day nua",
    "muon bien mat mai mai",
    "nghi (?:toi|den) chuyen (?:chet|tu tu)",
  ].join("|"),
);

// A cue inside a retold film, story or news scene is not the user's own risk unless they speak in first person.
const retoldScene = new RegExp(
  `(?:nhân vật|trong phim|trong truyện|bộ phim|cuốn truyện|tin tức|bản tin|bài báo)[^.!?]{0,60}`,
  "gu",
);
const firstPersonIntent = phrases(
  "(?:mình|tôi|tui|tớ|tao)(?: \\S+){0,2} (?:muốn|định|sẽ|đã|đang|nghĩ)",
);

const hasDiacritics = (text: string): boolean => /[à-ỹđ]/u.test(text);

export const crisisCue = (message: string): boolean => {
  const text = message
    .normalize("NFC")
    .toLowerCase()
    .replace(retoldScene, (scene) =>
      firstPersonIntent.test(scene) ? scene : "",
    );
  if (CRISIS_ACCENTED.some((pattern) => pattern.test(text))) return true;
  if (FAREWELL.test(text) && FAREWELL_RISK.test(text)) return true;
  if (hasDiacritics(text)) return false;
  return CRISIS_UNACCENTED.test(text);
};

const ADVICE = phrases("loi khuyen|khuyen (?:minh|toi)");
const HELP = phrases(
  [
    "nen lam gi",
    "phai lam gi",
    "phai lam sao",
    "lam the nao",
    "lam gi (?:bay )?gio",
    "lam sao (?:de|bay gio|day|gio)",
    "co cach nao",
    "cach nao (?:de|giup)",
    "giai quyet (?:sao|the nao|nhu the nao)",
    "giup (?:minh|toi) (?:voi|di|nhe|nha|duoc khong)",
    "(?:nao|gi) giup (?:minh|toi)",
    "bat dau tu dau",
    "(?:nen|phai) xu ly",
    "can lam gi",
    "(?:mo|tim|vao|den|di|viet|xem|co) (?:o dau|cho nao)",
    "co bai nao",
    "co bai doc nao",
    "loi khuyen",
    "khuyen (?:minh|toi)",
  ].join("|"),
);
// Venting such as “chẳng ai giúp mình cả” or “tìm ở đâu cũng không có” is not a request.
const notARequest =
  /\b(?:chang ai|khong ai|khong co|cung (?:khong|chang))\b[^.,!?]{0,30}|\b\S+ (?:o dau|cho nao) cung\b/gu;

const directHelp = (message: string): boolean =>
  HELP.test(plain(message).replace(notARequest, ""));

const outsideScope = (message: string): boolean => {
  const text = plain(message).replace(
    /\bbai tap (?:tho|thu gian)\b/gu,
    "exercise",
  );
  return (
    /\b(viet|giai|debug|sua|tao|lap trinh)\b.{0,70}\b(code|python|javascript|typescript|bai tap|bai toan|de bai|luan van)\b/u.test(
      text,
    ) ||
    /\b(lam ho|giai ho|viet ho|giup minh|giup toi|lam giup)\b.{0,70}\b(code|python|javascript|typescript|bai tap|bai toan|de bai|luan van)\b/u.test(
      text,
    ) ||
    /\b(code|python|javascript|typescript|bai tap|bai toan|de bai|luan van)\b.{0,70}\b(giup|lam ho|giai ho|viet ho|cho minh|cho toi)\b/u.test(
      text,
    )
  );
};

const shortAnswer = (text: string): boolean =>
  plain(text)
    .split(/[^a-z0-9]+/u)
    .filter(Boolean).length <= 3;

// Thanks, agreeing to try something or feeling better closes the topic with a short warm reply.
const CLOSING = phrases(
  [
    "cam on",
    "thanks?",
    "thank you",
    "(?:de minh|minh se|toi se|tui se) thu",
    "thu xem",
    "ngu ngon",
    "tam biet",
    "bye",
    "(?<!(?:khong|chang|chua) )(?:da|thay) (?:do|on|nhe|vui|khoe) hon",
  ].join("|"),
);
// The user asks to dig into it, or does not know why they feel this way.
const EXPLORE = phrases(
  [
    "hoi (?:minh|toi|tui) (?:di|nha|nhe|them)",
    "(?:muon|cho minh) (?:ke|noi|tam su) them",
    "giup (?:minh|toi) hieu",
    "(?:khong|chang) (?:biet|hieu) (?:tai |vi )?sao",
  ].join("|"),
);

// “Cảm ơn nha, nhưng mình vẫn buồn” or “thử xem rồi mà vẫn không được” keeps the topic open.
const STILL_OPEN = phrases(
  "nhung|ma (?:van|khong|chua)|van (?:khong|chua|con)",
);
const closing = (message: string): boolean => {
  const text = plain(message);
  return CLOSING.test(text) && !STILL_OPEN.test(text);
};

const modeFor = (message: string, topic: readonly string[]): ResponseMode => {
  if (crisisCue(message)) return "SAFETY";
  const advice = ADVICE.test(plain(message));
  if (!advice && outsideScope(message)) return "BOUNDARY";
  if (advice || directHelp(message)) return "SUPPORT_AND_WRAP_UP";
  if (closing(message)) return "LISTEN";
  // Ask only on a missing-info signal: the user wants to explore, or every answer so far is a word or two.
  if (EXPLORE.test(plain(message))) return "CLARIFY";
  const answers = [...topic, message];
  if (answers.length <= 3 && answers.every(shortAnswer)) return "CLARIFY";
  return topic.length >= 2 ? "SUPPORT_AND_WRAP_UP" : "LISTEN";
};

/**
 * Picks the turn's mode from the message and recent same-conversation turns.
 * Pass every recent turn (roles in order), not the byte-capped provider history: long messages must still reach wrap-up.
 * Replies need not end with a question, so the topic is rebuilt from the user turns alone: it restarts after a
 * turn whose mode closed it (wrap-up, boundary, safety) or after a thanks or closing message.
 */
export const selectResponseMode = (
  message: string,
  recentTurns: readonly HistoryTurn[],
): ResponseMode => {
  let topic: string[] = [];
  for (const turn of recentTurns) {
    if (turn.role !== "USER") continue;
    const mode = modeFor(turn.text, topic);
    topic =
      closing(turn.text) || (mode !== "LISTEN" && mode !== "CLARIFY")
        ? []
        : [...topic, turn.text];
  }
  return modeFor(message, topic);
};

const CRISIS_OVERRIDE =
  " The crisis rule in the system prompt overrides this directive: if the message contains any self-harm, suicide or immediate-danger cue, ignore this mode and give the fixed crisis reply with null suggestion. An unexplained final goodbye, giving belongings away or saying others would be better off without them counts as such a cue; never answer it with a warm farewell.";

export const responseModeDirective = (mode: ResponseMode): string => {
  switch (mode) {
    case "LISTEN":
      return `Server response_mode: LISTEN. The user is venting, sharing or closing. Listen and comfort: show you get the specific feeling behind what they said, then stop. No question is needed; end with a statement and use no question mark. If they are very sad, empathy first and no joking. If they are thanking you, agreeing to try something or feeling better, keep it short and light, encourage them and let the topic close. Give no tip, exercise or app offer.${CRISIS_OVERRIDE}`;
    case "CLARIFY":
      return `Server response_mode: CLARIFY. Essential information is missing or the user wants to explore. React briefly and warmly to what they said, then ask exactly one specific question (it may be a reflective either/or question). Nothing after the question. Give no tip, exercise or app offer.${CRISIS_OVERRIDE}`;
    case "SUPPORT_AND_WRAP_UP":
      return `Server response_mode: SUPPORT_AND_WRAP_UP. There is enough context: give helpful guidance and end naturally. Show in a few words what you understood (do not retell their story), comfort them, then suggest one or two concrete practical steps fitted to those details, phrased softly (bạn thử…, bạn có thể…). If the user already chose a direction, propose the next small step for it and do not ask again. For grief, loss or self-blame, the step is a gentle way to let the feeling be or to remember them, such as keeping one favourite memory of them. Choose steps that fit this exact situation; do not default to writing something down. End with a statement. Use no question mark anywhere, not even inside words you suggest they say. Do not ask a question unless information critical for immediate safety is missing. Do not merely comfort or offer a generic exercise. Depth: when the user has shared a lot on this topic or the situation is complex, write a full reply of about 1000–1500 characters in four to six short paragraphs (the specific feeling and why it makes sense, then two or three concrete steps with how to start each, then a natural ending); for a simple direct ask a shorter reply is fine. Never pad or repeat yourself.${CRISIS_OVERRIDE}`;
    case "BOUNDARY":
      return `Server response_mode: BOUNDARY. Mầm supports feelings and helps the user approach a problem; do not write code, solve homework or complete an assignment. If the user asks Mầm to do such a task, briefly decline it, acknowledge the pressure, and offer one practical way to approach it. If they only vent about the workload, comfort them without a question. Keep it short.${CRISIS_OVERRIDE}`;
    case "SAFETY":
      return "Server response_mode: SAFETY. Use the existing fixed crisis reply exactly, with null suggestion. No question, humour, emoji or other advice.";
  }
};
