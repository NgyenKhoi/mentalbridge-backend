import SwaggerParser from "@apidevtools/swagger-parser";
import { fileURLToPath } from "node:url";

const contractPath = fileURLToPath(
  new URL("../contracts/openapi/journal-ai-service-v1.yaml", import.meta.url),
);

const contract = await SwaggerParser.validate(contractPath);

if (contract.openapi !== "3.1.0") {
  throw new Error("Journal/AI OpenAPI contract must use version 3.1.0");
}

if (!contract.paths?.["/api/v1/journals"]) {
  throw new Error("Journal API must use the /api/v1 prefix");
}

if (contract.paths["/v1/journals"]) {
  throw new Error("Unversioned API-prefix contract path is not allowed");
}

const schemas = contract.components?.schemas;
const journalEntry = schemas?.JournalEntry;
const journalList = schemas?.JournalListResponse;
const detailContent = journalEntry?.allOf?.[1]?.properties?.content;
const listContent =
  journalList?.properties?.items?.items?.allOf?.[1]?.properties?.content;

if (!detailContent?.required?.includes("text")) {
  throw new Error("Journal detail responses must return authorized full content");
}

if (
  !listContent?.required?.includes("preview") ||
  listContent.required.includes("text")
) {
  throw new Error("Journal list responses must use minimized summaries");
}

console.log("Validated OpenAPI contract: contracts/openapi/journal-ai-service-v1.yaml");
