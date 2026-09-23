import SwaggerParser from '@apidevtools/swagger-parser';
import { fileURLToPath } from 'node:url';

const contractPath = fileURLToPath(
  new URL('../../contracts/openapi/content-notification-service.yaml', import.meta.url),
);
const contract = await SwaggerParser.validate(contractPath);

const expectedImplemented = new Set([
  'GET /health/live',
  'GET /health/ready',
  'GET /api/v1/resources',
  'POST /api/v1/resources',
  'GET /api/v1/resources/admin/list',
  'GET /api/v1/resources/admin/{id}',
  'GET /api/v1/resources/{id}',
  'PATCH /api/v1/resources/{id}',
  'DELETE /api/v1/resources/{id}',
  'POST /api/v1/resources/{id}/publish',
  'POST /api/v1/resources/{id}/archive',
  'POST /api/v1/resources/{id}/versions/{contentVersion}/eligibility-publications',
  'POST /api/v1/resources/{id}/versions/{contentVersion}/eligibility-publications/withdrawal',
  'POST /internal/v1/resource-eligibility:resolve',
  'GET /api/v1/safety-directory/admin/entries',
  'POST /api/v1/safety-directory/admin/entries',
  'PATCH /api/v1/safety-directory/admin/entries/{entryId}',
  'POST /api/v1/safety-directory/admin/entries/{entryId}/review',
  'POST /api/v1/safety-directory/admin/entries/{entryId}/deactivate',
  'POST /api/v1/safety-directory:lookup',
]);

const implementedResponses = new Map([
  ['GET /health/live', new Set(['200'])],
  ['GET /health/ready', new Set(['200', '503'])],
  ['GET /api/v1/resources', new Set(['200', '400'])],
  ['POST /api/v1/resources', new Set(['201', '400', '401', '403', '409', '422'])],
  ['GET /api/v1/resources/admin/list', new Set(['200', '400', '401', '403', '503'])],
  ['GET /api/v1/resources/admin/{id}', new Set(['200', '400', '401', '403', '404'])],
  ['GET /api/v1/resources/{id}', new Set(['200', '400', '404'])],
  ['PATCH /api/v1/resources/{id}', new Set(['200', '400', '401', '403', '409'])],
  ['DELETE /api/v1/resources/{id}', new Set(['204', '400', '401', '403', '409'])],
  ['POST /api/v1/resources/{id}/publish', new Set(['400', '401', '403', '409'])],
  ['POST /api/v1/resources/{id}/archive', new Set(['200', '400', '401', '403', '409'])],
  [
    'POST /api/v1/resources/{id}/versions/{contentVersion}/eligibility-publications',
    new Set(['201', '400', '401', '403', '404', '409', '422', '503']),
  ],
  [
    'POST /api/v1/resources/{id}/versions/{contentVersion}/eligibility-publications/withdrawal',
    new Set(['200', '400', '401', '403', '404', '409', '422', '503']),
  ],
  [
    'POST /internal/v1/resource-eligibility:resolve',
    new Set(['200', '400', '401', '403', '422', '503']),
  ],
  ['GET /api/v1/safety-directory/admin/entries', new Set(['200', '401', '403', '503'])],
  [
    'POST /api/v1/safety-directory/admin/entries',
    new Set(['201', '400', '401', '403', '409', '422', '503']),
  ],
  [
    'PATCH /api/v1/safety-directory/admin/entries/{entryId}',
    new Set(['200', '400', '401', '403', '409', '422', '503']),
  ],
  [
    'POST /api/v1/safety-directory/admin/entries/{entryId}/review',
    new Set(['200', '400', '401', '403', '409', '503']),
  ],
  [
    'POST /api/v1/safety-directory/admin/entries/{entryId}/deactivate',
    new Set(['200', '400', '401', '403', '409', '503']),
  ],
  ['POST /api/v1/safety-directory:lookup', new Set(['200', '422', '503'])],
]);

const implementedMustBePublic = new Set([
  'GET /health/live',
  'GET /health/ready',
  'GET /api/v1/resources',
  'GET /api/v1/resources/{id}',
  'POST /api/v1/safety-directory:lookup',
]);
const implementedMustBeProtected = new Set(
  [...expectedImplemented].filter((operation) => !implementedMustBePublic.has(operation)),
);

const actualImplemented = new Set();
const actualPlanned = new Set();
const methods = ['get', 'post', 'put', 'patch', 'delete'];

for (const [path, pathItem] of Object.entries(contract.paths ?? {})) {
  const pathStatus = pathItem['x-mentalbridge-status'];
  if (!['implemented', 'planned', 'partial'].includes(pathStatus)) {
    throw new Error(
      `${path} must declare x-mentalbridge-status as implemented, planned, or partial`,
    );
  }

  for (const method of methods) {
    if (!pathItem[method]) continue;

    const operation = `${method.toUpperCase()} ${path}`;
    const operationStatus = pathItem[method]['x-mentalbridge-operation-status'];
    const effectiveStatus = operationStatus ?? pathStatus;

    if (effectiveStatus === 'partial') {
      throw new Error(
        `${operation} resolved to partial status — set x-mentalbridge-operation-status`,
      );
    }

    (effectiveStatus === 'implemented' ? actualImplemented : actualPlanned).add(operation);

    if (effectiveStatus === 'implemented') {
      const expectedCodes = implementedResponses.get(operation);
      if (
        expectedCodes &&
        !setsEqual(new Set(Object.keys(pathItem[method].responses ?? {})), expectedCodes)
      ) {
        throw new Error(`${operation} response statuses differ from the implemented boundary`);
      }
      if (implementedMustBePublic.has(operation) && pathItem[method].security) {
        throw new Error(`${operation} must remain public`);
      }
      if (implementedMustBeProtected.has(operation) && !pathItem[method].security) {
        throw new Error(`${operation} must declare bearer authentication`);
      }
    }
  }
}

if (!setsEqual(actualImplemented, expectedImplemented)) {
  const missing = [...expectedImplemented].filter((op) => !actualImplemented.has(op));
  const extra = [...actualImplemented].filter((op) => !expectedImplemented.has(op));
  throw new Error(
    `Content contract availability differs from implemented controllers. Missing: [${missing.join(', ')}]. Extra: [${extra.join(', ')}]`,
  );
}

const domains = new Set(contract.components.schemas.ScreeningDomain.enum);
if (domains.has('GENERAL_WELLBEING') || domains.has('BOTH_SCREENED_DOMAINS')) {
  throw new Error('Pseudo-domains cannot enter Resource Eligibility v1');
}

const outcomes = new Set(contract.components.schemas.ResourceEligibilityOutcome.enum);
if (
  !setsEqual(
    outcomes,
    new Set(['ELIGIBLE', 'INELIGIBLE', 'STALE', 'WITHDRAWN', 'NOT_FOUND', 'UNAVAILABLE']),
  )
) {
  throw new Error('Resource eligibility outcomes differ from the frozen v1 boundary');
}

console.log('Validated OpenAPI contract: ../contracts/openapi/content-notification-service.yaml');

function setsEqual(left, right) {
  if (!right) return true;
  return left.size === right.size && [...left].every((value) => right.has(value));
}
