import { createCipheriv, createDecipheriv, createHmac, randomBytes } from 'node:crypto';

import { Inject, Injectable } from '@nestjs/common';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import { CONFIGURATION_TOKEN } from '../shared/tokens.js';

export interface EncryptedMessageBody {
  readonly bodyCiphertext: string;
  readonly bodyIv: string;
  readonly bodyTag: string;
  readonly keyVersion: string;
}

@Injectable()
export class MessageEncryptionService {
  constructor(@Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration) {}

  encrypt(content: string): EncryptedMessageBody {
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.configuration.MESSAGE_ENCRYPTION_KEY, iv);
    const ciphertext = Buffer.concat([cipher.update(content, 'utf8'), cipher.final()]);
    return {
      bodyCiphertext: ciphertext.toString('base64'),
      bodyIv: iv.toString('base64'),
      bodyTag: cipher.getAuthTag().toString('base64'),
      keyVersion: this.configuration.MESSAGE_ENCRYPTION_KEY_VERSION,
    };
  }

  decrypt(body: EncryptedMessageBody): string {
    const key = this.configuration.MESSAGE_DECRYPTION_KEYS[body.keyVersion];
    if (!key) {
      throw new Error('Message encryption key version is unavailable');
    }
    const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(body.bodyIv, 'base64'));
    decipher.setAuthTag(Buffer.from(body.bodyTag, 'base64'));
    return Buffer.concat([
      decipher.update(Buffer.from(body.bodyCiphertext, 'base64')),
      decipher.final(),
    ]).toString('utf8');
  }

  fingerprint(
    conversationId: string,
    type: string,
    content: string,
    keyVersion = this.configuration.MESSAGE_ENCRYPTION_KEY_VERSION,
  ): string {
    const key = this.configuration.MESSAGE_DECRYPTION_KEYS[keyVersion];
    if (!key) throw new Error('Message encryption key version is unavailable');
    return createHmac('sha256', key)
      .update(JSON.stringify({ conversationId, type, content }))
      .digest('base64url');
  }
}
