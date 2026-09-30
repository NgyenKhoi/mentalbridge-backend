import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';
import pg from 'pg';

const { Client } = pg;

export function normalizeSourceText(html) {
  return html
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;|&apos;/gi, "'")
    .replace(/\s+/g, ' ')
    .trim();
}

export function sourceContentHash(normalizedText) {
  return createHash('sha256').update(normalizedText, 'utf8').digest('hex');
}

function argument(name) {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

async function fetchSource(url) {
  const response = await fetch(url, {
    headers: { 'user-agent': 'MentalBridgeSourceReview/1.0 (+offline-curation)' },
    redirect: 'follow',
    signal: AbortSignal.timeout(20_000),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  const normalized = normalizeSourceText(await response.text());
  if (normalized.length < 100) throw new Error('normalized source is unexpectedly short');
  return {
    finalUrl: response.url,
    contentHash: sourceContentHash(normalized),
    normalizedCharacterCount: normalized.length,
  };
}

async function main() {
  if (process.argv.includes('--help')) {
    console.log(
      'Usage: npm run source:ingest -- --output <manifest.json> [--database-url <postgres-url>]',
    );
    return;
  }
  const databaseUrl = argument('--database-url') ?? process.env.DATABASE_URL;
  const output = argument('--output');
  if (!databaseUrl || !output) {
    throw new Error('DATABASE_URL/--database-url and --output are required');
  }

  const client = new Client({ connectionString: databaseUrl });
  await client.connect();
  let rows;
  try {
    ({ rows } = await client.query(
      `SELECT id, source_url, source_content_hash
       FROM resource
       WHERE source_url IS NOT NULL
       ORDER BY id`,
    ));
  } finally {
    await client.end();
  }

  const retrievedAt = new Date().toISOString();
  const sourceCache = new Map();
  const items = [];
  for (const row of rows) {
    let result = sourceCache.get(row.source_url);
    if (!result) {
      try {
        result = { status: 'FETCHED_PENDING_HUMAN_REVIEW', ...(await fetchSource(row.source_url)) };
      } catch (error) {
        result = {
          status: 'FETCH_FAILED',
          error: error instanceof Error ? error.message : String(error),
        };
      }
      sourceCache.set(row.source_url, result);
    }
    items.push({
      resourceId: row.id,
      requestedUrl: row.source_url,
      previousContentHash: row.source_content_hash,
      ...result,
    });
  }

  const target = resolve(output);
  await mkdir(dirname(target), { recursive: true });
  await writeFile(
    target,
    `${JSON.stringify({ schemaVersion: 1, retrievedAt, items }, null, 2)}\n`,
    'utf8',
  );
  console.log(`Wrote ${items.length} resource source records to ${target}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  });
}
