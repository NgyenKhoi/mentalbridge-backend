import { Inject, Injectable, type OnApplicationShutdown } from '@nestjs/common';
import { type Collection, type Document, MongoClient } from 'mongodb';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import { CONFIGURATION_TOKEN } from '../shared/tokens.js';

@Injectable()
export class MongoDatabaseService implements OnApplicationShutdown {
  private readonly client: MongoClient;
  private readonly databaseName: string;

  constructor(@Inject(CONFIGURATION_TOKEN) configuration: ServiceConfiguration) {
    this.client = new MongoClient(configuration.MONGODB_URI, {
      connectTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
      serverSelectionTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
    });
    this.databaseName = configuration.MONGODB_DATABASE;
  }

  collection<T extends Document>(name: string): Collection<T> {
    return this.client.db(this.databaseName).collection<T>(name);
  }

  async ping(): Promise<void> {
    await this.client.db(this.databaseName).command({ ping: 1 });
  }

  async onApplicationShutdown(): Promise<void> {
    await this.client.close();
  }
}
