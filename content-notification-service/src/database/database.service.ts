import { Inject, Injectable, type OnModuleDestroy } from '@nestjs/common';
import { Pool, type QueryResult, type QueryResultRow } from 'pg';

import { CONFIGURATION_TOKEN } from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';

type QueryParameter = string | number | boolean | Date | Buffer | null;

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
      host: configuration.DB_HOST,
      port: configuration.DB_PORT,
      database: configuration.DB_NAME,
      user: configuration.DB_USER,
      password: configuration.DB_PASSWORD,
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

  async check(): Promise<void> {
    await this.pool.query('SELECT 1');
  }

  async onModuleDestroy(): Promise<void> {
    await this.pool.end();
  }
}
