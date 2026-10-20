import { Module } from '@nestjs/common';
import { CommunityOwnerClient } from './community-owner.client.js';
import { CommunityCredentials, CommunityCredentialController } from './community-credentials.js';
import { CommunityFanout } from './community-fanout.js';
import { CommunityGateway } from './community.gateway.js';

@Module({
  controllers: [CommunityCredentialController],
  providers: [CommunityOwnerClient, CommunityCredentials, CommunityFanout, CommunityGateway],
  exports: [CommunityFanout],
})
export class CommunityModule {}
