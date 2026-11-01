import SwaggerParser from '@apidevtools/swagger-parser';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

describe('reminder overview OpenAPI boundary', () => {
  it('publishes the authenticated read separately from existing versioned settings writes', async () => {
    const api = await SwaggerParser.dereference(
      fileURLToPath(
        new URL('../../../contracts/openapi/content-notification-service.yaml', import.meta.url),
      ),
    );
    const operation = api.paths?.['/api/v1/reminder-overview']?.get;
    expect(operation?.security).toEqual([{ BearerAuth: [] }]);
    expect(operation?.parameters).toBeUndefined();
    expect(operation?.responses).toHaveProperty('409');
    expect(operation?.responses).toHaveProperty('503');
    expect(api.paths?.['/api/v1/notification-preferences']?.patch?.parameters).toEqual(
      expect.arrayContaining([expect.objectContaining({ name: 'If-Match', required: true })]),
    );
    const schemas = api.components?.schemas as Record<
      string,
      {
        properties: Record<
          string,
          {
            const?: unknown;
            enum?: unknown;
            maxItems?: number;
            items?: { properties: Record<string, unknown> };
          }
        >;
      }
    >;
    expect(schemas.ReminderOverview.properties.schemaVersion.const).toBe('1.0');
    expect(schemas.ReminderPreview.properties.estimate.const).toBe(true);
    expect(schemas.ReminderOverview.properties.today.maxItems).toBe(2);
    expect(schemas.ReminderOverview.properties.previews.maxItems).toBe(5);
    expect(schemas.ReminderActivity.properties.status.enum).toEqual(['DUE', 'COMPLETED']);
    expect(JSON.stringify(schemas.ReminderActivity)).not.toMatch(
      /streak|score|journalText|emotionNote|ownerAccountId/,
    );
  });
});
