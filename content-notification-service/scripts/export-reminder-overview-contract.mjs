import SwaggerParser from '@apidevtools/swagger-parser';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const check = args[0] === '--check';
const output = args[check ? 1 : 0];
if (!output) throw new Error('Provide the consumer contract snapshot path.');
const api = await SwaggerParser.dereference(
  fileURLToPath(
    new URL('../../contracts/openapi/content-notification-service.yaml', import.meta.url),
  ),
);
const content = `${JSON.stringify({ source: 'contracts/openapi/content-notification-service.yaml#/components/schemas/ReminderOverview', apiVersion: api.info.version, schema: api.components.schemas.ReminderOverview }, null, 2)}\n`;
if (check) {
  if (
    JSON.stringify(JSON.parse(readFileSync(output, 'utf8'))) !== JSON.stringify(JSON.parse(content))
  )
    throw new Error('Reminder consumer snapshot differs from the provider OpenAPI.');
  console.log('Reminder consumer snapshot matches provider OpenAPI.');
} else writeFileSync(output, content, 'utf8');
