export const corpus = [
  {
    id: "vi00",
    text: "",
    condition: "silence_or_noise_only",
    highImpact: false,
  },
  {
    id: "vi01",
    text: "Hôm nay mình muốn chia sẻ một chuyện nhỏ.",
    condition: "neutral",
    highImpact: false,
  },
  {
    id: "vi02",
    text: "Mình hơi căng thẳng vì buổi thuyết trình ngày mai.",
    condition: "soft",
    highImpact: false,
  },
  {
    id: "vi03",
    text: "Mình muốn nói chậm một chút rồi mới kể tiếp.",
    condition: "hesitant",
    highImpact: false,
  },
  {
    id: "vi04",
    text: "Để mình nghĩ thêm rồi mình sẽ nói tiếp nhé.",
    condition: "long_pause",
    highImpact: false,
  },
  {
    id: "vi05",
    text: "Tuần này deadline và buổi meeting khiến mình khá bận.",
    condition: "loanwords",
    highImpact: false,
  },
  {
    id: "vi06",
    text: "Mình muốn nghe cách đọc PHQ-9 và GAD-7.",
    condition: "abbreviations",
    highImpact: true,
  },
  {
    id: "vi07",
    text: "Mình không muốn dừng cuộc gọi lúc này.",
    condition: "negation",
    highImpact: true,
  },
  {
    id: "vi08",
    text: "Mình muốn dừng cuộc gọi ngay bây giờ.",
    condition: "barge_in",
    highImpact: true,
  },
  {
    id: "vi09",
    text: "Đây chỉ là câu giả lập: tôi không có ý định làm hại bản thân.",
    condition: "negation",
    highImpact: true,
  },
  {
    id: "vi10",
    text: "Bạn có thể nhắc lại bằng một câu ngắn hơn không?",
    condition: "question",
    highImpact: false,
  },
  {
    id: "vi11",
    text: "Chín giờ tối mình thường tắt điện thoại và nghỉ ngơi.",
    condition: "numbers",
    highImpact: false,
  },
  {
    id: "vi12",
    text: "Cảm ơn bạn đã lắng nghe. Mình muốn kết thúc ở đây.",
    condition: "ending",
    highImpact: false,
  },
] as const;
export const sttModels = ["deepgram/nova-3", "deepgram/nova-2"] as const;
export const ttsModel = "cartesia/sonic-3-2026-01-12" as const;
export const devices = ["laptop", "android"] as const;
export const noises = [
  "quiet",
  "fan_ac",
  "road",
  "surrounding_speech",
] as const;
export const regions = ["north", "central", "south", "other"] as const;
export const denoiseModes = [
  "webrtc_baseline",
  "agent_isolation_only",
] as const;
