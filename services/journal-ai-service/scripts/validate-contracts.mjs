import SwaggerParser from "@apidevtools/swagger-parser";
import { fileURLToPath } from "node:url";

const contractPath = fileURLToPath(
  new URL("../contracts/openapi/journal-ai-service-v1.yaml", import.meta.url),
);

await SwaggerParser.validate(contractPath);

console.log("Validated OpenAPI contract: contracts/openapi/journal-ai-service-v1.yaml");
