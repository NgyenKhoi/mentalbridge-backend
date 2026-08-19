import 'dotenv/config';
import { readFileSync } from 'fs';
import { join } from 'path';
import { pool } from './db';

async function migrate(): Promise<void> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const migrationPath = join(__dirname, '../../../../migrations/001_content_schema_baseline.sql');
    const sql = readFileSync(migrationPath, 'utf8');
    await client.query(sql);

    await client.query('COMMIT');
    console.log('Migration completed successfully.');
  } catch (err) {
    await client.query('ROLLBACK');
    console.error('Migration failed:', err);
    process.exit(1);
  } finally {
    client.release();
    await pool.end();
  }
}

migrate();
