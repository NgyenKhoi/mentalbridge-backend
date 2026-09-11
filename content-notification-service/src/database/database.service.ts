import { Inject, Injectable, type OnModuleDestroy } from '@nestjs/common';
import { Pool, type PoolClient, type QueryResult, type QueryResultRow } from 'pg';

import { CONFIGURATION_TOKEN } from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';

type QueryParameter = string | number | boolean | Date | Buffer | null;

export interface DatabaseClient {
  query<T extends QueryResultRow = QueryResultRow>(
    text: string,
    parameters?: QueryParameter[],
  ): Promise<QueryResult<T>>;
}

export interface ReadinessProbe {
  check(): Promise<void>;
}

@Injectable()
export class DatabaseService implements ReadinessProbe, OnModuleDestroy {
  private readonly pool: Pool;

  constructor(
    @Inject(CONFIGURATION_TOKEN)
    configuration: ServiceConfiguration,
  ) {
    this.pool = new Pool({
      connectionString: configuration.DATABASE_URL,
      max: configuration.DB_POOL_MAX,
      idleTimeoutMillis: configuration.DB_IDLE_TIMEOUT_MS,
      connectionTimeoutMillis: configuration.DB_CONNECT_TIMEOUT_MS,
    });
  }

  query<T extends QueryResultRow = QueryResultRow>(
    text: string,
    parameters?: QueryParameter[],
  ): Promise<QueryResult<T>> {
    return this.pool.query<T>(text, parameters);
  }

  async withTransaction<T>(operation: (client: DatabaseClient) => Promise<T>): Promise<T> {
    const client: PoolClient = await this.pool.connect();
    try {
      await client.query('BEGIN');
      const result = await operation(client);
      await client.query('COMMIT');
      return result;
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      client.release();
    }
  }

  async check(): Promise<void> {
    await this.pool.query('SELECT 1');
  }

  async onModuleDestroy(): Promise<void> {
    await this.pool.end();
  }
}
