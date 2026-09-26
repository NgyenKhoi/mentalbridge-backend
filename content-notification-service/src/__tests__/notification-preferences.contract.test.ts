import SwaggerParser from '@apidevtools/swagger-parser';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

describe('notification preference contract', () => {
  const contractPath = fileURLToPath(
    new URL('../../../contracts/openapi/content-notification-service.yaml', import.meta.url),
  );

  it('keeps owner operations authenticated, versioned and non-cacheable', async () => {
    const api = await SwaggerParser.dereference(contractPath);
    const path = api.paths?.['/api/v1/notification-preferences'] as {
      get?: Record<string, unknown>;
      patch?: Record<string, unknown>;
    };

    expect(path.get?.security).toEqual([{ BearerAuth: [] }]);
    expect(path.patch?.security).toEqual([{ BearerAuth: [] }]);
    expect(path.patch?.parameters).toEqual(
      expect.arrayContaining([expect.objectContaining({ name: 'If-Match', required: true })]),
    );
    const getResponses = path.get?.responses as Record<
      string,
      { headers?: Record<string, { schema?: { const?: string } }> }
    >;
    expect(getResponses['200'].headers?.['Cache-Control']?.schema?.const).toBe('private, no-store');
  });

  it('covers all channels, content groups, quiet hours and explicit email choices', async () => {
    const api = await SwaggerParser.dereference(contractPath);
    const schemas = (api.components as { schemas: Record<string, unknown> }).schemas;
    const preferences = schemas['NotificationPreferences'] as {
      required: string[];
      properties: Record<string, unknown>;
    };
    const channels = schemas['NotificationChannels'] as {
      required: string[];
      properties: Record<string, unknown>;
    };
    const groups = schemas['NotificationContentGroups'] as {
      required: string[];
      properties: Record<string, unknown>;
    };
    const email = schemas['EmailPreferences'] as {
      required: string[];
      properties: Record<string, unknown>;
    };

    expect(preferences.required).toEqual(
      expect.arrayContaining([
        'notificationsEnabled',
        'channels',
        'contentGroups',
        'quietHours',
        'email',
        'version',
      ]),
    );
    expect(channels.required).toEqual(['inApp', 'email', 'push']);
    expect(groups.required).toEqual([
      'journalReminder',
      'emotionCheckIn',
      'streakMilestone',
      'screeningReassessment',
      'appointmentMessage',
      'resourceSystem',
    ]);
    expect(email.required).toEqual([
      'cadence',
      'wellbeingDigestEnabled',
      'resourceRemindersEnabled',
    ]);
    expect(JSON.stringify(preferences.properties)).not.toMatch(
      /journalText|assessmentAnswer|messageBody|specialistName/i,
    );
  });
});
