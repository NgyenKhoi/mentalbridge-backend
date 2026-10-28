import {
  Body,
  Controller,
  Delete,
  HttpCode,
  HttpStatus,
  Inject,
  Param,
  Put,
  UnprocessableEntityException,
  UseGuards,
} from '@nestjs/common';

import { PUSH_DEVICE_SERVICE_TOKEN } from '../application.tokens.js';
import { CurrentUser } from '../auth/current-user.decorator.js';
import { JwtAuthGuard } from '../auth/jwt-auth.guard.js';
import type { AuthenticatedUser } from '../auth/jwt.strategy.js';
import { PushInstallationIdSchema } from './push-device.dto.js';
import type { PushDeviceService } from './push-device.service.js';
import type { PushDeviceRegistration } from './push-device.types.js';

function installationId(value: string): string {
  if (PushInstallationIdSchema.safeParse(value).success) return value;
  throw new UnprocessableEntityException({
    type: 'https://mentalbridge.io/errors/PUSH_DEVICE_VALIDATION_FAILED',
    title: 'Push device registration is invalid',
    status: 422,
    code: 'PUSH_DEVICE_VALIDATION_FAILED',
  });
}

@Controller('api/v1/push-devices')
@UseGuards(JwtAuthGuard)
export class PushDeviceController {
  constructor(@Inject(PUSH_DEVICE_SERVICE_TOKEN) private readonly service: PushDeviceService) {}

  @Put(':installationId')
  register(
    @CurrentUser() user: AuthenticatedUser,
    @Param('installationId') rawInstallationId: string,
    @Body() body: unknown,
  ): Promise<PushDeviceRegistration> {
    return this.service.register(user.accountId, installationId(rawInstallationId), body);
  }

  @Delete(':installationId')
  @HttpCode(HttpStatus.NO_CONTENT)
  revoke(
    @CurrentUser() user: AuthenticatedUser,
    @Param('installationId') rawInstallationId: string,
  ): Promise<void> {
    return this.service.revoke(user.accountId, installationId(rawInstallationId));
  }
}
