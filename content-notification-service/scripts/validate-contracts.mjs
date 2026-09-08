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
  'GET /api/v1/resources/{id}',
  'PATCH /api/v1/resources/{id}',
  'DELETE /api/v1/resources/{id}',
  'POST /api/v1/resources/{id}/publish',
  'POST /api/v1/resources/{id}/archive',
]);

const implementedResponses = new Map([
  ['GET /health/live', new Set(['200'])],
  ['GET /health/ready', new Set(['200', '503'])],
  ['GET /api/v1/resources', new Set(['200', '400'])],
  ['POST /api/v1/resources', new Set(['201', '422'])],
  ['GET /api/v1/resources/{id}', new Set(['200', '404'])],
  ['PATCH /api/v1/resources/{id}', new Set(['200', '404', '409'])],
  ['DELETE /api/v1/resources/{id}', new Set(['204', '404', '409'])],
  ['POST /api/v1/resources/{id}/publish', new Set(['200', '404', '409'])],
  ['POST /api/v1/resources/{id}/archive', new Set(['200', '404', '409'])],
]);

const implementedMustBePublic = new Set(['GET /health/live', 'GET /health/ready']);

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

console.log('Validated OpenAPI contract: ../contracts/openapi/content-notification-service.yaml');

function setsEqual(left, right) {
  if (!right) return true;
  return left.size === right.size && [...left].every((value) => right.has(value));
}
