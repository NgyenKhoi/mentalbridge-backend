import "reflect-metadata";

import {
  Controller,
  Get,
  Header,
  Inject,
  Module,
  type DynamicModule,
  type INestApplication,
  type MiddlewareConsumer,
  type NestModule,
} from "@nestjs/common";
import { NestFactory } from "@nestjs/core";

import {
  loadConfiguration,
  type ServiceConfiguration,
} from "./configuration/configuration.js";
import { createLogger } from "./observability/logger.js";
import { createMetrics, type Metrics } from "./observability/metrics.js";
import { RequestLoggingMiddleware } from "./observability/request-logging.middleware.js";
import {
  CONFIGURATION_TOKEN,
  LOGGER_TOKEN,
  METRICS_TOKEN,
} from "./observability/tokens.js";

interface HealthResponse {
  status: "ok";
  service: string;
  environment: ServiceConfiguration["NODE_ENV"];
}

@Controller("health")
class HealthController {
  constructor(
    @Inject(CONFIGURATION_TOKEN)
    private readonly configuration: ServiceConfiguration,
    @Inject(METRICS_TOKEN) private readonly metrics: Metrics,
  ) {}

  @Get("live")
  live(): HealthResponse {
    this.metrics.healthChecksTotal.inc({ endpoint: "live" });

    return {
      status: "ok",
      service: this.configuration.SERVICE_NAME,
      environment: this.configuration.NODE_ENV,
    };
  }

  @Get("ready")
  ready(): HealthResponse {
    this.metrics.healthChecksTotal.inc({ endpoint: "ready" });

    return {
      status: "ok",
      service: this.configuration.SERVICE_NAME,
      environment: this.configuration.NODE_ENV,
    };
  }
}

@Controller()
class MetricsController {
  constructor(@Inject(METRICS_TOKEN) private readonly metrics: Metrics) {}

  @Get("metrics")
  @Header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
  metricsText(): Promise<string> {
    return this.metrics.render();
  }
}

@Module({})
export class AppModule implements NestModule {
  static register(configuration: ServiceConfiguration): DynamicModule {
    const logger = createLogger(configuration);
    const metrics = createMetrics(configuration);

    return {
      module: AppModule,
      controllers: [HealthController, MetricsController],
      providers: [
        RequestLoggingMiddleware,
        {
          provide: CONFIGURATION_TOKEN,
          useValue: configuration,
        },
        {
          provide: LOGGER_TOKEN,
          useValue: logger,
        },
        {
          provide: METRICS_TOKEN,
          useValue: metrics,
        },
      ],
    };
  }

  configure(consumer: MiddlewareConsumer): void {
    consumer.apply(RequestLoggingMiddleware).forRoutes("*");
  }
}

export const createApplication = async (
  configuration = loadConfiguration(),
): Promise<INestApplication> =>
  NestFactory.create(AppModule.register(configuration), {
    logger: false,
  });
