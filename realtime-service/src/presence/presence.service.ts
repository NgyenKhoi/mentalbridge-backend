import { Inject, Injectable } from '@nestjs/common';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import { RedisService } from '../database/redis.service.js';
import { CONFIGURATION_TOKEN } from '../shared/tokens.js';

export type PresenceResult = 'connected' | 'degraded' | 'limit_exceeded';

@Injectable()
export class PresenceService {
  constructor(
    private readonly redis: RedisService,
    @Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration,
  ) {}

  async register(accountId: string, socketId: string): Promise<PresenceResult> {
    try {
      return await this.redis.execute(async (client) => {
        const accountKey = this.accountKey(accountId);
        const result = await client.eval(
          `local socket_ids = redis.call('SMEMBERS', KEYS[1])
           local live_count = 0
           for _, existing_socket_id in ipairs(socket_ids) do
             if redis.call('EXISTS', 'realtime:v1:socket:' .. existing_socket_id) == 1 then
               live_count = live_count + 1
             else
               redis.call('SREM', KEYS[1], existing_socket_id)
             end
           end
           if live_count >= tonumber(ARGV[4]) then return 0 end
           redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
           redis.call('SADD', KEYS[1], ARGV[1])
           redis.call('EXPIRE', KEYS[1], ARGV[3])
           return 1`,
          {
            keys: [accountKey, this.socketKey(socketId)],
            arguments: [
              socketId,
              accountId,
              String(this.configuration.PRESENCE_TTL_SECONDS),
              String(this.configuration.MAX_CONNECTIONS_PER_ACCOUNT),
            ],
          },
        );
        return result === 1 ? 'connected' : 'limit_exceeded';
      });
    } catch {
      return 'degraded';
    }
  }

  async heartbeat(accountId: string, socketId: string): Promise<PresenceResult> {
    try {
      return await this.redis.execute(async (client) => {
        const storedAccountId = await client.get(this.socketKey(socketId));
        if (storedAccountId !== accountId) return 'degraded';
        await client
          .multi()
          .expire(this.socketKey(socketId), this.configuration.PRESENCE_TTL_SECONDS)
          .expire(this.accountKey(accountId), this.configuration.PRESENCE_TTL_SECONDS)
          .exec();
        return 'connected';
      });
    } catch {
      return 'degraded';
    }
  }

  async remove(accountId: string, socketId: string): Promise<void> {
    try {
      await this.redis.execute(async (client) => {
        await client
          .multi()
          .del(this.socketKey(socketId))
          .sRem(this.accountKey(accountId), socketId)
          .exec();
      });
    } catch {
      return;
    }
  }

  async status(accountId: string): Promise<'online' | 'offline' | 'unknown'> {
    try {
      return await this.redis.execute(async (client) => {
        const socketIds = await client.sMembers(this.accountKey(accountId));
        for (const socketId of socketIds) {
          if (await client.exists(this.socketKey(socketId))) return 'online';
        }
        return 'offline';
      });
    } catch {
      return 'unknown';
    }
  }

  private socketKey(socketId: string): string {
    return `realtime:v1:socket:${socketId}`;
  }

  private accountKey(accountId: string): string {
    return `realtime:v1:account:${accountId}:connections`;
  }
}
