import { config } from "dotenv";
import { z } from "zod";

const schema = z.object({ PORT: z.coerce.number().int().min(1).max(65535).default(3000), MONGODB_URI: z.string().url().default("mongodb://localhost:27017"), MONGODB_DATABASE: z.string().min(1).default("mentalbridge_journal_ai") });
export type Configuration = z.infer<typeof schema>;
export const loadConfiguration = (env: NodeJS.ProcessEnv = process.env): Configuration => { config({ override: false, quiet: true }); return schema.parse(env); };
