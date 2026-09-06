import { Writable } from 'node:stream';

import { describe, expect, it } from 'vitest';

import { createLogger } from '../../src/observability/logger.js';
import { testConfiguration } from '../fixtures/test-configuration.js';

describe('Structured logging', () => {
  it('redacts authentication and message content fields', () => {
    let output = '';
    const destination = new Writable({
      write(chunk, _encoding, callback) {
        output += String(chunk);
        callback();
      },
    });
    const logger = createLogger(testConfiguration('unused', { LOG_LEVEL: 'info' }), destination);
    logger.info({
      req: { headers: { authorization: 'Bearer secret-token', cookie: 'session=secret' } },
      handshake: { auth: { accessToken: 'socket-secret' } },
      accessToken: 'direct-secret',
      content: 'sensitive chat',
      payload: { content: 'nested sensitive chat' },
      bodyCiphertext: 'encrypted-payload',
    });
    expect(output).toContain('[redacted]');
    for (const secret of [
      'secret-token',
      'session=secret',
      'socket-secret',
      'direct-secret',
      'sensitive chat',
      'nested sensitive chat',
      'encrypted-payload',
    ]) {
      expect(output).not.toContain(secret);
    }
  });
});
