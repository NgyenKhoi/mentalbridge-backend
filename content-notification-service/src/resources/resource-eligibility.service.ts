import {
  ConflictException,
  Inject,
  Injectable,
  Logger,
  NotFoundException,
  ServiceUnavailableException,
} from '@nestjs/common';

import {
  E2E_OUTAGE_STATE_TOKEN,
  RESOURCE_ELIGIBILITY_REPOSITORY_TOKEN,
} from '../application.tokens.js';
import type {
  PublishResourceEligibilityRequest,
  ResourceEligibilityBatchRequest,
  ResourceEligibilityBatchResponse,
  ResourceEligibilityPublication,
  WithdrawResourceEligibilityRequest,
} from '../generated/resource-eligibility.contract.js';
import type { E2eOutageState } from './resource.service.js';
import {
  EligibilityCommandConflictError,
  EligibilityResourceNotFoundError,
  type EligibilityCommandContext,
  type ResourceEligibilityRepository,
} from './resource-eligibility.repository.js';

@Injectable()
export class ResourceEligibilityService {
  private readonly logger = new Logger(ResourceEligibilityService.name);

  constructor(
    @Inject(RESOURCE_ELIGIBILITY_REPOSITORY_TOKEN)
    private readonly repository: ResourceEligibilityRepository,
    @Inject(E2E_OUTAGE_STATE_TOKEN)
    private readonly outageState: E2eOutageState,
  ) {}

  async publish(
    resourceId: string,
    contentVersion: string,
    request: PublishResourceEligibilityRequest,
    idempotencyKey: string,
    context: EligibilityCommandContext,
  ): Promise<ResourceEligibilityPublication> {
    try {
      return await this.repository.publish(
        resourceId,
        contentVersion,
        request,
        idempotencyKey,
        context,
      );
    } catch (error) {
      this.rethrowCommandError(error);
    }
  }

  async withdraw(
    resourceId: string,
    contentVersion: string,
    request: WithdrawResourceEligibilityRequest,
    idempotencyKey: string,
    context: EligibilityCommandContext,
  ): Promise<ResourceEligibilityPublication> {
    try {
      return await this.repository.withdraw(
        resourceId,
        contentVersion,
        request,
        idempotencyKey,
        context,
      );
    } catch (error) {
      this.rethrowCommandError(error);
    }
  }

  async resolve(
    request: ResourceEligibilityBatchRequest,
  ): Promise<ResourceEligibilityBatchResponse> {
    if (this.outageState.enabled) throw this.unavailable();
    try {
      return await this.repository.resolve(request.requests);
    } catch (error) {
      this.logger.warn({
        event: 'resource_eligibility_resolution_unavailable',
        code: databaseErrorCode(error),
      });
      throw this.unavailable();
    }
  }

  private rethrowCommandError(error: unknown): never {
    if (error instanceof EligibilityResourceNotFoundError) {
      throw new NotFoundException({
        type: 'https://mentalbridge.io/errors/RESOURCE_NOT_FOUND',
        title: 'Exact resource version or eligibility publication was not found',
        status: 404,
        code: 'RESOURCE_NOT_FOUND',
      });
    }
    if (error instanceof EligibilityCommandConflictError || databaseErrorCode(error) === '23505') {
      throw new ConflictException({
        type: 'https://mentalbridge.io/errors/ELIGIBILITY_PUBLICATION_CONFLICT',
        title: 'Eligibility publication conflicts with immutable resource state',
        status: 409,
        code: 'ELIGIBILITY_PUBLICATION_CONFLICT',
      });
    }
    this.logger.warn({
      event: 'resource_eligibility_command_unavailable',
      code: databaseErrorCode(error),
    });
    throw this.unavailable();
  }

  private unavailable(): ServiceUnavailableException {
    return new ServiceUnavailableException({
      type: 'https://mentalbridge.io/errors/DEPENDENCY_UNAVAILABLE',
      title: 'Resource eligibility is temporarily unavailable',
      status: 503,
      code: 'DEPENDENCY_UNAVAILABLE',
    });
  }
}

function databaseErrorCode(error: unknown): string | undefined {
  if (typeof error !== 'object' || error === null || !('code' in error)) return undefined;
  return typeof error.code === 'string' ? error.code : undefined;
}
