export const PUSH_CHANNEL_ID = 'mentalbridge-chime-v1';
export const PUSH_SOUND = 'mentalbridge_chime.wav';

const GENERIC_COPY = { title: 'MentalBridge', body: 'Bạn có thông báo mới trên MentalBridge.' };

const REVIEWED_COPY_KINDS: ReadonlySet<string> = new Set([
  'JOURNAL_REMINDER',
  'EMOTION_CHECKIN_REMINDER',
  'JOURNAL_STREAK_MILESTONE',
  'EMOTION_STREAK_MILESTONE',
  'APPOINTMENT',
]);

export function pushCopy(
  kind: string,
  title: string,
  body: string,
): { title: string; body: string } {
  return REVIEWED_COPY_KINDS.has(kind) ? { title, body } : { ...GENERIC_COPY };
}
