import type { INestApplicationContext } from '@nestjs/common';
import { IoAdapter } from '@nestjs/platform-socket.io';
import type { ServerOptions } from 'socket.io';

import type { ServiceConfiguration } from '../configuration/configuration.js';

export class ConfiguredSocketIoAdapter extends IoAdapter {
  constructor(
    app: INestApplicationContext,
    private readonly configuration: ServiceConfiguration,
  ) {
    super(app);
  }

  override createIOServer(port: number, options?: ServerOptions): unknown {
    return super.createIOServer(port, {
      ...options,
      maxHttpBufferSize: this.configuration.MAX_PAYLOAD_BYTES,
      cors: {
        origin: this.configuration.ALLOWED_ORIGINS.length
          ? this.configuration.ALLOWED_ORIGINS
          : false,
        credentials: true,
      },
    });
  }
}
