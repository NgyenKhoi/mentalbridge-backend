import { SetMetadata } from "@nestjs/common";

export const PUBLIC_ROUTE_METADATA = "mentalbridge.public-route";

export const Public = (): MethodDecorator & ClassDecorator =>
  SetMetadata(PUBLIC_ROUTE_METADATA, true);
