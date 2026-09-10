import { Injectable, UnauthorizedException, Inject } from '@nestjs/common';
import { PassportStrategy } from '@nestjs/passport';
import { Strategy, ExtractJwt } from 'passport-jwt';
import type { ServiceConfiguration } from '../configuration/configuration.js';
import { CONFIGURATION_TOKEN } from '../application.tokens.js';

export interface JwtPayload {
  sub: string;
  iss: string;
  aud: string | string[];
  exp: number;
  iat: number;
  nbf: number;
  jti: string;
  roles: string[];
}

export interface AuthenticatedUser {
  accountId: string;
  roles: string[];
}

@Injectable()
export class JwtStrategy extends PassportStrategy(Strategy) {
  constructor(@Inject(CONFIGURATION_TOKEN) config: ServiceConfiguration) {
    super({
      jwtFromRequest: ExtractJwt.fromAuthHeaderAsBearerToken(),
      ignoreExpiration: false,
      secretOrKey: config.IDENTITY_JWT_PUBLIC_KEY,
      algorithms: ['RS256'],
      issuer: config.IDENTITY_JWT_ISSUER,
      audience: config.IDENTITY_JWT_AUDIENCE,
    });
  }

  validate(payload: JwtPayload): AuthenticatedUser {
    if (!payload.sub || !Array.isArray(payload.roles)) {
      throw new UnauthorizedException('Invalid token payload');
    }

    return {
      accountId: payload.sub,
      roles: payload.roles,
    };
  }
}
