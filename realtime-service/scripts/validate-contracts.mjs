import SwaggerParser from '@apidevtools/swagger-parser';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const openApiPath = fileURLToPath(
  new URL('../../contracts/openapi/realtime-service-v1.yaml', import.meta.url),
);
const openApi = await SwaggerParser.validate(openApiPath);
if (openApi.openapi !== '3.1.0') throw new Error('Realtime OpenAPI must use version 3.1.0');

const expectedAvailability = new Map([
  ['GET /health/live', 'implemented'],
  ['GET /health/ready', 'implemented'],
  ['GET /metrics', 'implemented'],
  ['GET /api/v1/conversations/{conversationId}/messages', 'planned'],
]);
const actualAvailability = new Map();
for (const [path, pathItem] of Object.entries(openApi.paths ?? {})) {
  const availability = pathItem['x-mentalbridge-status'];
  if (availability !== 'implemented' && availability !== 'planned') {
    throw new Error(`${path} must declare implemented or planned availability`);
  }
  for (const method of ['get', 'post', 'put', 'patch', 'delete']) {
    if (pathItem[method]) actualAvailability.set(`${method.toUpperCase()} ${path}`, availability);
  }
}
if (
  expectedAvailability.size !== actualAvailability.size ||
  [...expectedAvailability].some(
    ([operation, availability]) => actualAvailability.get(operation) !== availability,
  )
) {
  throw new Error('Realtime OpenAPI operation availability differs from the approved baseline');
}

const ajv = new Ajv2020({ strict: true, allErrors: true });
addFormats(ajv);
for (const name of [
  'handshake-v1.schema.json',
  'command-envelope-v1.schema.json',
  'acknowledgement-v1.schema.json',
  'error-v1.schema.json',
  'server-event-v1.schema.json',
]) {
  const path = new URL(`../../contracts/websocket/realtime/${name}`, import.meta.url);
  const schema = JSON.parse(await readFile(path, 'utf8'));
  ajv.compile(schema);
}

console.log('Validated Realtime OpenAPI and WebSocket v1 contracts');
