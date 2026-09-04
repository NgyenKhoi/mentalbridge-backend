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

const implemented = new Set([
  'GET /health/live',
  'GET /health/ready',
  'GET /metrics',
  'GET /api/v1/conversations/{conversationId}/messages',
]);
const actual = new Set();
for (const [path, pathItem] of Object.entries(openApi.paths ?? {})) {
  if (pathItem['x-mentalbridge-status'] !== 'implemented') {
    throw new Error(`${path} must declare its implemented availability`);
  }
  for (const method of ['get', 'post', 'put', 'patch', 'delete']) {
    if (pathItem[method]) actual.add(`${method.toUpperCase()} ${path}`);
  }
}
if (
  implemented.size !== actual.size ||
  [...implemented].some((operation) => !actual.has(operation))
) {
  throw new Error('Realtime OpenAPI operations differ from implemented controllers');
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
