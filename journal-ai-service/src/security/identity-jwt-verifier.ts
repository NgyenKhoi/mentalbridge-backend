import { Inject, Injectable, type OnModuleInit } from "@nestjs/common";
import { importSPKI, jwtVerify, type CryptoKey, type JWTPayload } from "jose";

import type { ServiceConfiguration } from "../configuration/configuration.js";
import { CONFIGURATION_TOKEN } from "../observability/tokens.js";
import type { AuthenticatedPrincipal } from "./authenticated-principal.js";

const accountIdPattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

@Injectable()
export class IdentityJwtVerifier implements OnModuleInit {
  private verificationKey?: CryptoKey;

  constructor(
    @Inject(CONFIGURATION_TOKEN)
    private readonly configuration: ServiceConfiguration,
  ) {}

  async onModuleInit(): Promise<void> {
    this.verificationKey = await importSPKI(
      this.configuration.IDENTITY_JWT_PUBLIC_KEY,
      "RS256",
    );
  }

  async verify(token: string): Promise<AuthenticatedPrincipal> {
    const verificationKey = this.verificationKey;

    if (!verificationKey) {
      throw new Error("Identity JWT verifier is not initialized");
    }

    const { payload, protectedHeader } = await jwtVerify(
      token,
      verificationKey,
      {
        algorithms: ["RS256"],
        issuer: this.configuration.IDENTITY_JWT_ISSUER,
        audience: this.configuration.IDENTITY_JWT_AUDIENCE,
        requiredClaims: ["sub", "iat", "nbf", "exp", "jti", "roles"],
      },
    );

    if (protectedHeader.kid !== this.configuration.IDENTITY_JWT_KEY_ID) {
      throw new Error("Identity JWT key ID is invalid");
    }

    return this.toPrincipal(payload);
  }

  private toPrincipal(payload: JWTPayload): AuthenticatedPrincipal {
    if (!payload.sub || !accountIdPattern.test(payload.sub)) {
      throw new Error("Identity JWT subject is invalid");
    }

    const roles = payload.roles;
    if (
      !Array.isArray(roles) ||
      roles.length === 0 ||
      roles.some((role) => typeof role !== "string" || role.length === 0)
    ) {
      throw new Error("Identity JWT roles are invalid");
    }

    return {
      accountId: payload.sub,
      roles,
      ...(payload.jti ? { tokenId: payload.jti } : {}),
    };
  }
}
