import SwaggerParser from '@apidevtools/swagger-parser';
import { fileURLToPath } from 'node:url';

const contractPath = fileURLToPath(
  new URL('../../contracts/openapi/content-notification-service.yaml', import.meta.url),
);
const contract = await SwaggerParser.validate(contractPath);
const expectedImplemented = new Set(['GET /health/live', 'GET /health/ready']);
const actualImplemented = new Set();
const actualPlanned = new Set();
const methods = ['get', 'post', 'put', 'patch', 'delete'];

for (const [path, pathItem] of Object.entries(contract.paths ?? {})) {
  const status = pathItem['x-mentalbridge-status'];
  if (status !== 'implemented' && status !== 'planned') {
    throw new Error(`${path} must declare x-mentalbridge-status`);
  }

  for (const method of methods) {
    if (pathItem[method]) {
      const operation = `${method.toUpperCase()} ${path}`;
      (status === 'implemented' ? actualImplemented : actualPlanned).add(operation);
    }
  }
}

if (!setsEqual(actualImplemented, expectedImplemented)) {
  throw new Error('Content contract availability differs from implemented controllers');
}
if (actualPlanned.size === 0) {
  throw new Error('Forward-looking Content operations must remain explicitly planned');
}

console.log('Validated OpenAPI contract: ../contracts/openapi/content-notification-service.yaml');

function setsEqual(left, right) {
  return left.size === right.size && [...left].every((value) => right.has(value));
}
