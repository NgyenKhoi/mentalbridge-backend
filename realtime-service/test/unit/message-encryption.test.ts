import { describe, expect, it } from 'vitest';

import { MessageEncryptionService } from '../../src/messages/message-encryption.service.js';
import { testConfiguration } from '../fixtures/test-configuration.js';

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
});
