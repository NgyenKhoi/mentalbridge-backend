export type ActorRole = 'USER' | 'SPECIALIST' | 'ADMIN';

export interface AuthenticatedPrincipal {
  readonly accountId: string;
  readonly role: ActorRole;
  readonly tokenId: string;
}

export interface AuthenticatedRequest {
  readonly headers: Readonly<Record<string, string | string[] | undefined>>;
  principal?: AuthenticatedPrincipal;
  id?: string;
}
