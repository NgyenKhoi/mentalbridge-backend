import { Pool, QueryResult, QueryResultRow } from 'pg';

const pool = new Pool({
  host: process.env.CONTENT_DB_HOST ?? 'localhost',
  port: parseInt(process.env.CONTENT_DB_PORT ?? '5432', 10),
  database: process.env.CONTENT_DB_NAME ?? 'mentalbridge',
  user: process.env.CONTENT_DB_USER ?? 'content_svc',
  password: process.env.CONTENT_DB_PASSWORD,
  max: 10,
  idleTimeoutMillis: 30_000,
  connectionTimeoutMillis: 2_000,
});

export function query<T extends QueryResultRow = QueryResultRow>(
  text: string,
  params?: unknown[],
): Promise<QueryResult<T>> {
  return pool.query<T>(text, params);
}

export { pool };
