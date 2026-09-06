import { describe, expect, it } from 'vitest';

import { MessageEncryptionService } from '../../src/messages/message-encryption.service.js';
import { encryptionKey, testConfiguration } from '../fixtures/test-configuration.js';

describe('Message encryption', () => {
  it('round trips UTF-8 content without storing plaintext', () => {
    const service = new MessageEncryptionService(testConfiguration('unused'));
    const encrypted = service.encrypt('Mình cần được hỗ trợ');
    expect(encrypted.bodyCiphertext).not.toContain('hỗ trợ');
    expect(service.decrypt(encrypted)).toBe('Mình cần được hỗ trợ');
  });

  it('binds idempotency fingerprints to conversation and content', () => {
    const service = new MessageEncryptionService(testConfiguration('unused'));
    const first = service.fingerprint('conversation-a', 'TEXT', 'one');
    expect(service.fingerprint('conversation-a', 'TEXT', 'one')).toBe(first);
    expect(service.fingerprint('conversation-a', 'TEXT', 'two')).not.toBe(first);
    expect(service.fingerprint('conversation-b', 'TEXT', 'one')).not.toBe(first);
  });

  it('decrypts and fingerprints messages written by a previous key version', () => {
    const previousKey = Buffer.alloc(32, 3);
    const previousConfiguration = testConfiguration('public-key', {
      MESSAGE_ENCRYPTION_KEY: previousKey,
      MESSAGE_ENCRYPTION_KEY_VERSION: 'test-v0',
      MESSAGE_DECRYPTION_KEYS: { 'test-v0': previousKey },
    });
    const encrypted = new MessageEncryptionService(previousConfiguration).encrypt('old message');
    const rotatedConfiguration = testConfiguration('public-key', {
      MESSAGE_DECRYPTION_KEYS: {
        'test-v0': previousKey,
        'test-v1': encryptionKey,
      },
    });
    const rotated = new MessageEncryptionService(rotatedConfiguration);
    expect(rotated.decrypt(encrypted)).toBe('old message');
    expect(rotated.fingerprint('conversation', 'TEXT', 'old message', 'test-v0')).toBe(
      new MessageEncryptionService(previousConfiguration).fingerprint(
        'conversation',
        'TEXT',
        'old message',
      ),
    );
  });
});
