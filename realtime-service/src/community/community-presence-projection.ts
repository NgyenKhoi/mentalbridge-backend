import { Injectable } from '@nestjs/common';
import { CommunityOwnerClient } from './community-owner.client.js';
import { CommunityPresence } from './community-presence.js';

@Injectable()
export class CommunityPresenceProjection {
  constructor(
    private readonly owner: CommunityOwnerClient,
    private readonly presence: CommunityPresence,
  ) {}

  async snapshot(subject: string, conversation: string, correlation: string) {
    const peers = await this.owner.presence(subject, conversation, correlation);
    const sampledAt = Date.now();
    const states = await this.presence.states(peers.map((peer) => peer.subjectRoutingReference));
    const current = await this.owner.presence(subject, conversation, correlation);
    const checkedAt = Date.now();
    const projections = peers.flatMap((peer, index) => {
      const state = states[index];
      if (
        !state ||
        !current.some(
          (value) =>
            value.communityProfileId === peer.communityProfileId &&
            value.subjectRoutingReference === peer.subjectRoutingReference,
        )
      )
        return [];
      const expiresAt = sampledAt + state.expiresIn;
      return [
        {
          communityProfileId: peer.communityProfileId,
          state:
            state.state === 'ONLINE' && expiresAt <= checkedAt ? ('UNKNOWN' as const) : state.state,
          routing: peer.subjectRoutingReference,
          expiresAt,
        },
      ];
    });
    return {
      projections,
      refreshAt: Math.min(
        checkedAt + 5000,
        ...projections.filter((value) => value.state === 'ONLINE').map((value) => value.expiresAt),
      ),
    };
  }
}
