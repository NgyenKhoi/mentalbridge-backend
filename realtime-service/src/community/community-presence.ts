import { Injectable } from '@nestjs/common';
import { RedisService } from '../database/redis.service.js';
import { CommunityError } from './community-schema.js';

export interface PresenceState {
  subject: string;
  state: 'ONLINE' | 'OFFLINE' | 'UNKNOWN';
  expiresIn: number;
}

const heartbeatScript = `
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
local before = redis.call('ZCARD', KEYS[1])
if ARGV[2] == '0' then
  redis.call('ZREM', KEYS[1], ARGV[1])
else
  if tonumber(ARGV[3]) <= now then return -2 end
  if not redis.call('SET', KEYS[2], '1', 'NX', 'PX', 10000) then return -1 end
  redis.call('ZADD', KEYS[1], math.min(now + 60000, tonumber(ARGV[3])), ARGV[1])
  redis.call('PEXPIRE', KEYS[1], 65000)
end
local after = redis.call('ZCARD', KEYS[1])
if after == 0 then redis.call('DEL', KEYS[1]) end
if (before == 0 and after > 0) or (before > 0 and after == 0) then return 1 end
return 0`;

const statesScript = `
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local result = {}
for _, key in ipairs(KEYS) do
  redis.call('ZREMRANGEBYSCORE', key, '-inf', now)
  local last = redis.call('ZRANGE', key, -1, -1, 'WITHSCORES')
  if #last == 0 then
    table.insert(result, 0)
  else
    table.insert(result, tonumber(last[2]) - now)
  end
end
return result`;

@Injectable()
export class CommunityPresence {
  constructor(private readonly redis: RedisService) {}

  async heartbeat(
    subject: string,
    socket: string,
    foreground: boolean,
    identityExpiresAt: number,
  ): Promise<boolean> {
    try {
      const result = await this.redis.execute((client) =>
        client.eval(heartbeatScript, {
          keys: [this.key(subject), this.rateKey(socket)],
          arguments: [socket, foreground ? '1' : '0', String(identityExpiresAt * 1000)],
        }),
      );
      if (result === -1) throw new CommunityError('RATE_LIMITED');
      if (result === -2) throw new CommunityError('UNAUTHENTICATED');
      return result === 1;
    } catch (error) {
      if (error instanceof CommunityError) throw error;
      throw new CommunityError('DEPENDENCY_UNAVAILABLE');
    }
  }

  async remove(subject: string, socket: string): Promise<boolean> {
    const changed = await this.heartbeat(subject, socket, false, 0);
    await this.redis.execute((client) => client.del(this.rateKey(socket)));
    return changed;
  }

  async states(subjects: string[]): Promise<PresenceState[]> {
    if (subjects.length === 0) return [];
    if (subjects.length > 30) throw new CommunityError('INVALID_COMMAND');
    try {
      const result = await this.redis.execute((client) =>
        client.eval(statesScript, {
          keys: subjects.map((subject) => this.key(subject)),
          arguments: [],
        }),
      );
      if (
        !Array.isArray(result) ||
        result.length !== subjects.length ||
        result.some((value) => typeof value !== 'number' || value < 0 || value > 60000)
      )
        throw new Error('Invalid presence state');
      return subjects.map((subject, index) => {
        const expiresIn = Number(result[index]);
        return { subject, state: expiresIn > 0 ? 'ONLINE' : 'OFFLINE', expiresIn };
      });
    } catch {
      return subjects.map((subject) => ({ subject, state: 'UNKNOWN', expiresIn: 0 }));
    }
  }

  private key(subject: string): string {
    return `community:v1:presence:${subject}`;
  }
  private rateKey(socket: string): string {
    return `community:v1:presence-heartbeat:${socket}`;
  }
}
