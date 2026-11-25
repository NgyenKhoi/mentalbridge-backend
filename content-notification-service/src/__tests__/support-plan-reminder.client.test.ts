import { ServiceUnavailableException } from '@nestjs/common';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import { SupportPlanReminderClient } from '../reminders/support-plan-reminder.client.js';

const OWNER = 'a13e4567-e89b-42d3-a456-426614174000';
const TOKEN = 'test-support-plan-reminder-token-at-least-32-chars';
const configuration = {
  CONTENT_CARE_BASE_URL: 'http://care.test:8081',
  CONTENT_CARE_TIMEOUT_MS: 700,
  CONTENT_CARE_SUPPORT_PLAN_REMINDER_SERVICE_TOKEN: TOKEN,
} as ServiceConfiguration;

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('SupportPlanReminderClient', () => {
  it('posts the owner and instant with the scoped service token and returns the boolean', async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json({ eligible: true }));
    vi.stubGlobal('fetch', fetch);

    await expect(
      new SupportPlanReminderClient(configuration).eligible(
        OWNER,
        new Date('2026-11-15T13:00:00.000Z'),
        'f872f72d-d5f1-48ce-a889-b7d1015347d6',
      ),
    ).resolves.toBe(true);

    const [url, request] = fetch.mock.calls[0] as [URL, RequestInit];
    expect(url.toString()).toBe(
      'http://care.test:8081/internal/v1/support-plan-reminder-eligibility',
    );
    expect(request.method).toBe('POST');
    expect(request.redirect).toBe('error');
    expect(request.signal).toBeInstanceOf(AbortSignal);
    const headers = new Headers(request.headers);
    expect(headers.get('x-mentalbridge-service-token')).toBe(TOKEN);
    expect(headers.get('x-correlation-id')).toBe('f872f72d-d5f1-48ce-a889-b7d1015347d6');
    expect(headers.get('authorization')).toBeNull();
    expect(JSON.parse(String(request.body))).toEqual({
      ownerAccountId: OWNER,
      asOf: '2026-11-15T13:00:00.000Z',
    });
  });

  it('defaults the Care base URL', async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json({ eligible: false }));
    vi.stubGlobal('fetch', fetch);

    await expect(
      new SupportPlanReminderClient({
        CONTENT_CARE_SUPPORT_PLAN_REMINDER_SERVICE_TOKEN: TOKEN,
      } as ServiceConfiguration).eligible(OWNER, new Date(), 'correlation'),
    ).resolves.toBe(false);
    expect(String(fetch.mock.calls[0][0])).toBe(
      'http://localhost:8081/internal/v1/support-plan-reminder-eligibility',
    );
  });

  it.each([
    ['non-2xx', () => Promise.resolve(new Response(null, { status: 401 }))],
    ['extra fields', () => Promise.resolve(Response.json({ eligible: true, title: 'private' }))],
    ['wrong type', () => Promise.resolve(Response.json({ eligible: 'yes' }))],
    ['network error', () => Promise.reject(new Error('ECONNREFUSED'))],
  ])('maps %s to unavailable', async (_name, response) => {
    vi.stubGlobal('fetch', vi.fn(response));

    await expect(
      new SupportPlanReminderClient(configuration).eligible(OWNER, new Date(), 'correlation'),
    ).rejects.toBeInstanceOf(ServiceUnavailableException);
  });

  it('aborts after the configured timeout', async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url: URL, request: RequestInit) =>
          new Promise((_resolve, reject) => {
            request.signal?.addEventListener('abort', () => {
              reject(new Error('aborted'));
            });
          }),
      ),
    );

    const pending = new SupportPlanReminderClient(configuration).eligible(
      OWNER,
      new Date(),
      'correlation',
    );
    const assertion = expect(pending).rejects.toBeInstanceOf(ServiceUnavailableException);
    await vi.advanceTimersByTimeAsync(700);
    await assertion;
  });

  it('reports unconfigured without calling Care', async () => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    const client = new SupportPlanReminderClient({} as ServiceConfiguration);

    expect(client.isConfigured()).toBe(false);
    expect(new SupportPlanReminderClient(configuration).isConfigured()).toBe(true);
    await expect(client.eligible(OWNER, new Date(), 'correlation')).rejects.toBeInstanceOf(
      ServiceUnavailableException,
    );
    expect(fetch).not.toHaveBeenCalled();
  });
});
