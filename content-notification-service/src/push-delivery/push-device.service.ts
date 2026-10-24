import {
  Inject,
  HttpException,
  HttpStatus,
  Injectable,
  NotFoundException,
  ServiceUnavailableException,
  UnprocessableEntityException,
} from '@nestjs/common';
import { ZodError } from 'zod';

import { CONFIGURATION_TOKEN, PUSH_DEVICE_REPOSITORY_TOKEN } from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import { PushDeviceRegistrationSchema } from './push-device.dto.js';
import type { PushDeviceRepository } from './push-device.repository.js';
import type { PushDeviceRegistration } from './push-device.types.js';
import { PushTokenCrypto } from './push-token.crypto.js';

const problem = (status: number, code: string, title: string) => ({
  type: `https://mentalbridge.io/errors/${code}`,
  title,
  status,
  code,
});

@Injectable()
export class PushDeviceService {
  constructor(
    @Inject(PUSH_DEVICE_REPOSITORY_TOKEN) private readonly repository: PushDeviceRepository,
    @Inject(CONFIGURATION_TOKEN) private readonly config: ServiceConfiguration,
  ) {}

  async register(
    ownerId: string,
    installationId: string,
    body: unknown,
  ): Promise<PushDeviceRegistration> {
    await this.enforceRateLimit(ownerId);
    if (!this.config.CONTENT_PUSH_TOKEN_ENCRYPTION_KEY) throw new ServiceUnavailableException();
    try {
      const input = PushDeviceRegistrationSchema.parse(body);
      return await this.repository.register(
        ownerId,
        { installationId, ...input },
        new PushTokenCrypto(this.config.CONTENT_PUSH_TOKEN_ENCRYPTION_KEY).encrypt(
          input.providerToken,
        ),
      );
    } catch (error) {
      if (!(error instanceof ZodError)) throw error;
      throw new UnprocessableEntityException({
        ...problem(422, 'PUSH_DEVICE_VALIDATION_FAILED', 'Push device registration is invalid'),
        fieldViolations: error.issues.map((issue) => ({
          field: issue.path.join('.') || 'body',
          message: issue.message,
        })),
      });
    }
  }

  async revoke(ownerId: string, installationId: string): Promise<void> {
    await this.enforceRateLimit(ownerId);
    if (!(await this.repository.revoke(ownerId, installationId)))
      throw new NotFoundException(problem(404, 'PUSH_DEVICE_NOT_FOUND', 'Push device not found'));
  }

  private async enforceRateLimit(ownerId: string): Promise<void> {
    const accepted = await this.repository.consumeCommandRateLimit(
      ownerId,
      this.config.CONTENT_PUSH_DEVICE_RATE_LIMIT,
      this.config.CONTENT_PUSH_DEVICE_RATE_WINDOW_SECONDS,
    );
    if (!accepted)
      throw new HttpException(
        problem(429, 'PUSH_DEVICE_RATE_LIMITED', 'Push device request rate exceeded'),
        HttpStatus.TOO_MANY_REQUESTS,
      );
  }
}
