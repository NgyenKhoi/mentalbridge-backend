import { Controller, Delete, ForbiddenException, Headers, Inject, Post } from '@nestjs/common';
import { CONFIGURATION_TOKEN, E2E_OUTAGE_STATE_TOKEN } from '../application.tokens.js';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import type { E2eOutageState } from '../resources/resource.service.js';

@Controller('__test/content')
export class E2eOutageController {
  constructor(
    @Inject(CONFIGURATION_TOKEN)
    private readonly configuration: ServiceConfiguration,
    @Inject(E2E_OUTAGE_STATE_TOKEN)
    private readonly outageState: E2eOutageState,
  ) {}

  @Post('outage')
  enable(@Headers('x-e2e-secret') secret?: string): { enabled: true } {
    this.authorize(secret);
    this.outageState.enabled = true;
    return { enabled: true };
  }

  @Delete('outage')
  disable(@Headers('x-e2e-secret') secret?: string): { enabled: false } {
    this.authorize(secret);
    this.outageState.enabled = false;
    return { enabled: false };
  }

  private authorize(secret?: string): void {
    if (
      !this.configuration.E2E_TEST_MODE ||
      !this.configuration.E2E_TEST_SECRET ||
      secret !== this.configuration.E2E_TEST_SECRET
    ) {
      throw new ForbiddenException('E2E controls are disabled');
    }
  }
}
