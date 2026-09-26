import SwaggerParser from '@apidevtools/swagger-parser';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

describe('notification inbox contract', () => {
  const contractPath = fileURLToPath(
    new URL('../../../contracts/openapi/content-notification-service.yaml', import.meta.url),
  );

  it('keeps all owner operations authenticated and non-cacheable', async () => {
    const api = await SwaggerParser.dereference(contractPath);
    const list = api.paths?.['/api/v1/notifications']?.get as Record<string, unknown>;
    const read = api.paths?.['/api/v1/notifications/{notificationId}/read']?.patch as Record<
      string,
      unknown
    >;
    const readAll = api.paths?.['/api/v1/notifications/mark-all-read']?.post as Record<
      string,
      unknown
    >;
    const remove = api.paths?.['/api/v1/notifications/{notificationId}']?.delete as Record<
      string,
      unknown
    >;

    for (const operation of [list, read, readAll, remove]) {
      expect(operation.security).toEqual([{ BearerAuth: [] }]);
    }
    const responses = list.responses as Record<
      string,
      { headers?: Record<string, { schema?: { const?: string } }> }
    >;
    expect(responses['200'].headers?.['Cache-Control']?.schema?.const).toBe('private, no-store');
  });

  it('exposes every mock kind, lifecycle metadata and only approved relative actions', async () => {
    const api = await SwaggerParser.dereference(contractPath);
    const schemas = (api.components as { schemas: Record<string, unknown> }).schemas;
    const kind = schemas['NotificationKind'] as { enum: string[] };
    const notification = schemas['Notification'] as {
      required: string[];
      properties: Record<string, unknown>;
    };
    const action = schemas['NotificationAction'] as { properties: Record<string, unknown> };

    expect(kind.enum).toEqual([
      'REMINDER',
      'MESSAGE',
      'APPOINTMENT',
      'SYSTEM_RESOURCE',
      'ASSESSMENT_REASSESSMENT',
      'STREAK_MILESTONE',
    ]);
    expect(notification.required).toEqual(
      expect.arrayContaining(['occurredAt', 'readAt', 'action', 'lifecycleState', 'expiresAt']),
    );
    expect(JSON.stringify(action.properties)).toContain('^/');
    expect(JSON.stringify({ notification, action })).not.toMatch(
      /journalText|assessmentAnswer|chatBody|selfReport|providerPayload|externalUrl/i,
    );
  });
});
