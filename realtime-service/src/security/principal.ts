export type ActorRole = 'USER' | 'SPECIALIST' | 'ADMIN';

export interface AuthenticatedPrincipal {
  readonly accountId: string;
  readonly role: ActorRole;
  readonly tokenId: string;
  readonly expiresAtEpochSeconds: number;
}

export interface AuthenticatedRequest {
  readonly headers: Readonly<Record<string, string | string[] | undefined>>;
  principal?: AuthenticatedPrincipal;
  bearerToken?: string;
  id?: string;
}
