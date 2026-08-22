import "reflect-metadata";

import {
  Controller,
  Get,
  Header,
  Inject,
  Module,
  ServiceUnavailableException,
  type DynamicModule,
  type INestApplication,
  type MiddlewareConsumer,
  type NestModule,
  type Provider,
} from "@nestjs/common";
import { APP_GUARD, NestFactory } from "@nestjs/core";

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
  READINESS_PROBE_TOKEN,
} from "./observability/tokens.js";
import {
  MongoReadinessProbe,
  type ReadinessProbe,
} from "./health/readiness.js";
import { IdentityJwtVerifier } from "./security/identity-jwt-verifier.js";
import { JwtAuthenticationGuard } from "./security/jwt-authentication.guard.js";
import { Public } from "./security/public.decorator.js";
import { ProblemDetailsFilter } from "./http/problem-details.filter.js";

interface HealthResponse {
  status: "ok";
  service: string;
  environment: ServiceConfiguration["NODE_ENV"];
}

@Public()
@Controller("health")
class HealthController {
  constructor(
    @Inject(CONFIGURATION_TOKEN)
    private readonly configuration: ServiceConfiguration,
    @Inject(METRICS_TOKEN) private readonly metrics: Metrics,
    @Inject(READINESS_PROBE_TOKEN)
    private readonly readinessProbe: ReadinessProbe,
  ) {}

  @Get("live")
  live(): HealthResponse {
    this.metrics.healthChecksTotal.inc({ endpoint: "live", result: "ok" });

    return {
      status: "ok",
      service: this.configuration.SERVICE_NAME,
      environment: this.configuration.NODE_ENV,
    };
  }

  @Get("ready")
  async ready(): Promise<HealthResponse> {
    try {
      await this.readinessProbe.check();
      this.metrics.healthChecksTotal.inc({ endpoint: "ready", result: "ok" });
    } catch {
      this.metrics.healthChecksTotal.inc({
        endpoint: "ready",
        result: "unavailable",
      });
      throw new ServiceUnavailableException("MongoDB is unavailable");
    }

    return {
      status: "ok",
      service: this.configuration.SERVICE_NAME,
      environment: this.configuration.NODE_ENV,
    };
  }
}

@Public()
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
  static register(
    configuration: ServiceConfiguration,
    dependencies: ApplicationDependencies = {},
  ): DynamicModule {
    const logger = createLogger(configuration);
    const metrics = createMetrics(configuration);
    const readinessProvider: Provider = dependencies.readinessProbe
      ? {
          provide: READINESS_PROBE_TOKEN,
          useValue: dependencies.readinessProbe,
        }
      : {
          provide: READINESS_PROBE_TOKEN,
          useClass: MongoReadinessProbe,
        };

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
        readinessProvider,
        IdentityJwtVerifier,
        {
          provide: APP_GUARD,
          useClass: JwtAuthenticationGuard,
        },
      ],
    };
  }

  configure(consumer: MiddlewareConsumer): void {
    consumer.apply(RequestLoggingMiddleware).forRoutes("*");
  }
}

export interface ApplicationDependencies {
  readonly readinessProbe?: ReadinessProbe;
}

export const createApplication = async (
  configuration = loadConfiguration(),
  dependencies: ApplicationDependencies = {},
): Promise<INestApplication> => {
  const app = await NestFactory.create(
    AppModule.register(configuration, dependencies),
    {
      logger: false,
    },
  );
  app.useGlobalFilters(new ProblemDetailsFilter());
  return app;
};
