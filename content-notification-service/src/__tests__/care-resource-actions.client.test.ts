import { ServiceUnavailableException } from '@nestjs/common';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { CareResourceActionsClient } from '../resources/care-resource-actions.client.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';

const projection = {
  planState: 'ACTIVE',
  supportPlanId: '67000000-0000-4000-8000-000000000001',
  supportPlanVersion: 2,
  timeZone: 'Asia/Ho_Chi_Minh',
  actions: [
    {
      actionId: 'v2:CORE:1',
      actionVersion: 2,
      resource: {
        resourceId: '67000000-0000-4000-8000-000000000002',
        resourceVersion: '3',
        publicationId: '67000000-0000-4000-8000-000000000003',
      },
    },
  ],
};

const client = () =>
  new CareResourceActionsClient({
    CONTENT_CARE_BASE_URL: 'https://care.test',
    CONTENT_CARE_TIMEOUT_MS: 100,
  } as ServiceConfiguration);

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('Care resource actions HTTP client', () => {
  it('forwards the owner bearer and correlation ID and consumes v2 adapter identities', async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify(projection), { status: 200 }));
    vi.stubGlobal('fetch', fetcher);
    const result = await client().get('Bearer owner-token', '2026-10-21', 'correlation-670');
    expect(result.actions[0]).toMatchObject({ actionId: 'v2:CORE:1', actionVersion: '2' });
    expect(fetcher).toHaveBeenCalledWith(
      new URL(
        'https://care.test/api/v2/support-plans/current/resource-actions?localDate=2026-10-21',
      ),
      expect.objectContaining({
        redirect: 'error',
        headers: {
          Accept: 'application/json',
          Authorization: 'Bearer owner-token',
          'X-Correlation-Id': 'correlation-670',
        },
      }),
    );
  });

  it.each([500, 503, 401, 404])(
    'fails closed with sanitized 503 for provider status %i',
    async (status) => {
      vi.stubGlobal(
        'fetch',
        vi.fn(async () => new Response('private provider detail', { status })),
      );
      await expect(client().get('Bearer owner-token', '2026-10-21')).rejects.toBeInstanceOf(
        ServiceUnavailableException,
      );
    },
  );

  it.each([
    { ...projection, supportPlanId: undefined },
    { ...projection, supportPlanVersion: undefined },
    { ...projection, actions: [{ ...projection.actions[0], resource: { resourceId: 'invalid' } }] },
    { ...projection, planState: 'NONE' },
  ])('rejects malformed projections without fabricating an empty plan', async (body) => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify(body), { status: 200 })),
    );
    await expect(client().get('Bearer owner-token', '2026-10-21')).rejects.toBeInstanceOf(
      ServiceUnavailableException,
    );
  });

  it('aborts at the configured deadline and reports dependency unavailability', async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_url: unknown, options: RequestInit) =>
          new Promise((_resolve, reject) => {
            options.signal?.addEventListener('abort', () => reject(new Error('aborted')));
          }),
      ),
    );
    const result = client().get('Bearer owner-token', '2026-10-21');
    const assertion = expect(result).rejects.toBeInstanceOf(ServiceUnavailableException);
    await vi.advanceTimersByTimeAsync(100);
    await assertion;
  });
});
