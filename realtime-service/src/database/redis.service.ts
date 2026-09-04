import { Inject, Injectable, type OnApplicationShutdown } from '@nestjs/common';
import { createClient, type RedisClientType } from 'redis';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import { CONFIGURATION_TOKEN } from '../shared/tokens.js';

@Injectable()
export class RedisService implements OnApplicationShutdown {
  private readonly client: RedisClientType;
  private connection: Promise<void> | undefined;

  constructor(@Inject(CONFIGURATION_TOKEN) configuration: ServiceConfiguration) {
    this.client = createClient({
      url: configuration.REDIS_URL,
      socket: {
        connectTimeout: configuration.REDIS_CONNECTION_TIMEOUT_MS,
        reconnectStrategy: false,
      },
    });
    this.client.on('error', () => undefined);
  }

  async execute<T>(operation: (client: RedisClientType) => Promise<T>): Promise<T> {
    await this.connect();
    return operation(this.client);
  }

  async ping(): Promise<boolean> {
    try {
      return (await this.execute((client) => client.ping())) === 'PONG';
    } catch {
      return false;
    }
  }

  async onApplicationShutdown(): Promise<void> {
    if (this.client.isOpen) {
      try {
        await this.client.quit();
      } catch {
        try {
          this.client.destroy();
        } catch {
          return;
        }
      }
    }
  }

  private async connect(): Promise<void> {
    if (this.client.isReady) return;
    this.connection ??= this.client
      .connect()
      .then(() => undefined)
      .finally(() => {
        this.connection = undefined;
      });
    await this.connection;
  }
}
