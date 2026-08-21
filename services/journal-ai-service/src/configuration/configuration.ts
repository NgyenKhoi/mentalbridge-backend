import { config as loadDotenv } from "dotenv";
import { z } from "zod";

const nodeEnvironments = ["development", "test", "production"] as const;

const environmentSchema = z.object({
  NODE_ENV: z.enum(nodeEnvironments).default("development"),
  PORT: z.coerce.number().int().min(1).max(65_535).default(3000),
  LOG_LEVEL: z
    .enum(["fatal", "error", "warn", "info", "debug", "trace", "silent"])
    .default("info"),
  SERVICE_NAME: z.string().min(1).default("journal-ai-service"),
});

export type ServiceConfiguration = z.infer<typeof environmentSchema>;

export const loadConfiguration = (
  environment: NodeJS.ProcessEnv = process.env,
): ServiceConfiguration => {
  const nodeEnvironment = environment.NODE_ENV ?? "development";

  if (nodeEnvironment !== "production") {
    loadDotenv({ override: false, quiet: true });
  }

  return environmentSchema.parse(environment);
};
