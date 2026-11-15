import { describe, expect, it } from 'vitest';

import { pushCopy } from '../push-delivery/push-copy.js';

const generic = { title: 'MentalBridge', body: 'Bạn có thông báo mới trên MentalBridge.' };

describe('push copy allowlist', () => {
  it.each([
    'JOURNAL_REMINDER',
    'EMOTION_CHECKIN_REMINDER',
    'JOURNAL_STREAK_MILESTONE',
    'EMOTION_STREAK_MILESTONE',
    'APPOINTMENT',
  ])('keeps the reviewed title and body for %s', (kind) => {
    expect(pushCopy(kind, 'Tiêu đề', 'Nội dung')).toEqual({ title: 'Tiêu đề', body: 'Nội dung' });
  });

  it('always pushes the fixed generic SupportPlan reminder copy', () => {
    expect(pushCopy('SUPPORT_PLAN_DAILY_REMINDER', 'Thở chậm 5 phút', '3 hoạt động')).toEqual({
      title: 'Bạn có hoạt động trong kế hoạch hôm nay',
      body: 'Mở MentalBridge để xem kế hoạch hỗ trợ hôm nay.',
    });
  });

  it.each([
    'COMMUNITY_COMMENT',
    'COMMUNITY_MESSAGE',
    'MESSAGE',
    'ASSESSMENT_RESULT_AVAILABLE',
    'ASSESSMENT_REASSESSMENT',
    'SYSTEM_RESOURCE',
    'SUPPORT_GUIDE_AVAILABLE',
    'ACCOUNT_STATUS',
    'SAFETY',
    'NOT_A_KIND',
  ])('falls back to the generic copy for %s', (kind) => {
    expect(pushCopy(kind, 'Một người đã trả lời', 'Nội dung riêng tư')).toEqual(generic);
  });
});
