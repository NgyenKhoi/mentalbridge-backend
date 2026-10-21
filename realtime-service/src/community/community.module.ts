import { Module } from '@nestjs/common';
import { CommunityOwnerClient } from './community-owner.client.js';
import { CommunityCredentials, CommunityCredentialController } from './community-credentials.js';
import { CommunityFanout } from './community-fanout.js';
import { CommunityGateway } from './community.gateway.js';
import { CommunityPresence } from './community-presence.js';
import { CommunityPresenceProjection } from './community-presence-projection.js';
import { CommunityNativeCredentialController } from './community-native-credential.controller.js';
import { SecurityModule } from '../security/security.module.js';

@Module({
  imports: [SecurityModule],
  controllers: [CommunityCredentialController, CommunityNativeCredentialController],
  providers: [
    CommunityOwnerClient,
    CommunityCredentials,
    CommunityFanout,
    CommunityPresence,
    CommunityPresenceProjection,
    CommunityGateway,
  ],
  exports: [CommunityFanout],
})
export class CommunityModule {}
