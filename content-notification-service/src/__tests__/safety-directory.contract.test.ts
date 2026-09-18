import SwaggerParser from '@apidevtools/swagger-parser';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * Contract tests for the Content-owned safety directory lookup boundary.
 *
 * These tests verify that the content-notification-service.yaml contract:
 *   - is valid and fully resolvable
 *   - exposes the lookup endpoint as public (no auth)
 *   - mandates the approved constant area wording with no nearest/proximity claim
 *   - restricts states to owner-level values (no UNAVAILABLE — that is Care-side)
 *   - requires provenance fields on every public entry
 *   - forbids coordinates, distance, or geolocation fields
 *   - enforces closed (additionalProperties: false) on request input
 *
 * Counterpart: SafetyDirectoryContractTests.java verifies the Care consumer side
 * in care-service-v1.yaml.
 */
describe('content safety directory contract', () => {
  const contractPath = fileURLToPath(
    new URL(
      '../../../contracts/openapi/content-notification-service.yaml',
      import.meta.url,
    ),
  );

  it('contract parses and resolves without errors', async () => {
    const api = await SwaggerParser.validate(contractPath);
    expect(api).toBeDefined();
    expect(api.info.title).toBeTruthy();
  });

  it('lookup endpoint is public and returns no-store cache header', async () => {
    const api = await SwaggerParser.dereference(contractPath);
    const lookupPath = api.paths?.['POST /api/v1/safety-directory:lookup'];

    const postOp = (api.paths as Record<string, unknown>)[
      '/api/v1/safety-directory:lookup'
    ] as { post?: Record<string, unknown> };
    expect(postOp?.post).toBeDefined();

    const post = postOp.post!;
    expect(post['security']).toBeFalsy();

    const okResponse = (
      post['responses'] as Record<string, { headers?: Record<string, unknown> }>
    )['200'];
    expect(okResponse).toBeDefined();
    const cacheHeader = okResponse.headers?.['Cache-Control'] as
      | { schema?: { const?: string } }
      | undefined;
    expect(cacheHeader?.schema?.const).toBe('no-store');

    void lookupPath;
  });

  it('lookup response wording is the constant approved text with no nearest claim', async () => {
    const api = await SwaggerParser.dereference(contractPath);
    const schemas = (api.components as { schemas: Record<string, unknown> })
      .schemas;
    const response = schemas['SafetyDirectoryLookupResponse'] as {
      required: string[];
      properties: Record<string, { const?: string; type?: unknown }>;
    };

    expect(response).toBeDefined();
    expect(response.required).toEqual(
      expect.arrayContaining([
        'state',
        'wording',
        'resolvedProvinceCode',
        'resolvedDistrictCode',
        'entries',
      ]),
    );

    const wordingSchema = response.properties['wording'];
    expect(wordingSchema?.const).toBe('Cơ sở trong khu vực đã chọn');
    expect(wordingSchema?.const).not.toMatch(/gần nhất|nearest/i);
  });

  it('lookup state enum contains only owner-level states and excludes UNAVAILABLE', async () => {
    const api = await SwaggerParser.dereference(contractPath);
    const schemas = (api.components as { schemas: Record<string, unknown> })
      .schemas;
    const stateSchema = schemas['SafetyDirectoryLookupState'] as {
      enum: string[];
    };

    expect(stateSchema).toBeDefined();
    expect(stateSchema.enum).toContain('RESULTS');
    expect(stateSchema.enum).toContain('EMPTY');
    expect(stateSchema.enum).toContain('INVALID_AREA');
    expect(stateSchema.enum).not.toContain('UNAVAILABLE');
    expect(stateSchema.enum).toHaveLength(3);
  });

  it('public entry schema requires provenance fields and forbids coordinates or nearest-distance', async () => {
    const api = await SwaggerParser.dereference(contractPath);
    const schemas = (api.components as { schemas: Record<string, unknown> })
      .schemas;
    const entry = schemas['SafetyDirectoryPublicEntry'] as {
      required: string[];
      properties: Record<string, unknown>;
    };

    expect(entry).toBeDefined();
    expect(entry.required).toEqual(
      expect.arrayContaining([
        'directoryEntryId',
        'name',
        'type',
        'phone',
        'address',
        'coverage',
        'sourceName',
        'sourceReference',
        'reviewedAt',
        'verifiedAt',
      ]),
    );

    const forbiddenFields = [
      'latitude',
      'longitude',
      'coordinates',
      'distance',
      'distanceMeters',
      'nearest',
      'location',
      'geoLocation',
    ];
    for (const field of forbiddenFields) {
      expect(Object.keys(entry.properties ?? {})).not.toContain(field);
    }
  });

  it('lookup request input schema is closed and permits only province/district/manual inputs', async () => {
    const api = await SwaggerParser.dereference(contractPath);
    const schemas = (api.components as { schemas: Record<string, unknown> })
      .schemas;
    const request = schemas['SafetyDirectoryLookupRequest'] as {
      additionalProperties: boolean | object;
      properties: Record<string, unknown>;
    };

    expect(request).toBeDefined();
    expect(request.additionalProperties).toBe(false);

    const allowed = new Set(Object.keys(request.properties ?? {}));
    expect(allowed).toEqual(
      new Set(['provinceCode', 'districtCode', 'manualLocation']),
    );

    const forbidden = [
      'latitude',
      'longitude',
      'coordinates',
      'geoLocation',
      'ipAddress',
      'trigger',
    ];
    for (const field of forbidden) {
      expect(allowed.has(field)).toBe(false);
    }
  });

  it('review state enum covers the full review lifecycle', async () => {
    const api = await SwaggerParser.dereference(contractPath);
    const schemas = (api.components as { schemas: Record<string, unknown> })
      .schemas;
    const reviewState = schemas['SafetyDirectoryReviewState'] as {
      enum: string[];
    };

    expect(reviewState).toBeDefined();
    expect(reviewState.enum).toEqual(
      expect.arrayContaining(['UNREVIEWED', 'CURRENT', 'STALE', 'INACTIVE']),
    );
  });

  it('admin endpoints require BearerAuth and lookup endpoint does not', async () => {
    const api = await SwaggerParser.dereference(contractPath);
    const paths = api.paths as Record<
      string,
      Record<string, { security?: Array<Record<string, unknown>> }>
    >;

    const adminPaths = [
      ['GET', '/api/v1/safety-directory/admin/entries'],
      ['POST', '/api/v1/safety-directory/admin/entries'],
      ['PATCH', '/api/v1/safety-directory/admin/entries/{entryId}'],
      ['POST', '/api/v1/safety-directory/admin/entries/{entryId}/review'],
      ['POST', '/api/v1/safety-directory/admin/entries/{entryId}/deactivate'],
    ] as const;

    for (const [method, path] of adminPaths) {
      const op = paths[path]?.[method.toLowerCase()];
      expect(op?.security ?? []).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ BearerAuth: expect.anything() }),
        ]),
      );
    }

    const lookupOp = paths['/api/v1/safety-directory:lookup']?.['post'];
    expect(lookupOp?.security).toBeFalsy();
  });
});
