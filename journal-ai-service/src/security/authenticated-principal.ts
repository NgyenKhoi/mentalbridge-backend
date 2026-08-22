export interface AuthenticatedPrincipal {
  readonly accountId: string;
  readonly roles: readonly string[];
  readonly tokenId?: string;
}

export interface AuthenticatedRequest {
  readonly headers: Readonly<Record<string, string | string[] | undefined>>;
  principal?: AuthenticatedPrincipal;
}
