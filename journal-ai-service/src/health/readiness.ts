import {
  Inject,
  Injectable,
  type OnApplicationShutdown,
} from "@nestjs/common";
import { MongoClient } from "mongodb";

import type { ServiceConfiguration } from "../configuration/configuration.js";
import { CONFIGURATION_TOKEN } from "../observability/tokens.js";

export interface ReadinessProbe {
  check(): Promise<void>;
}

@Injectable()
export class MongoReadinessProbe
  implements ReadinessProbe, OnApplicationShutdown
{
  private readonly client: MongoClient;

  constructor(
    @Inject(CONFIGURATION_TOKEN) configuration: ServiceConfiguration,
  ) {
    this.client = new MongoClient(configuration.MONGODB_URI, {
      connectTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
      serverSelectionTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
    });
    this.databaseName = configuration.MONGODB_DATABASE;
  }

  private readonly databaseName: string;

  async check(): Promise<void> {
    await this.client.db(this.databaseName).command({ ping: 1 });
  }

  async onApplicationShutdown(): Promise<void> {
    await this.client.close();
  }
}
