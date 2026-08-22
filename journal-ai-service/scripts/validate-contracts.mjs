import SwaggerParser from "@apidevtools/swagger-parser";
import { fileURLToPath } from "node:url";

const contractPath = fileURLToPath(
  new URL(
    "../../contracts/openapi/journal-ai-service-v1.yaml",
    import.meta.url,
  ),
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

const implementedOperations = new Set([
  "GET /health/live",
  "GET /health/ready",
  "GET /metrics",
]);
const implementedResponses = new Map([
  ["GET /health/live", new Set(["200"])],
  ["GET /health/ready", new Set(["200", "503"])],
  ["GET /metrics", new Set(["200"])],
]);
const plannedOperations = new Set([
  "POST /api/v1/journals",
  "GET /api/v1/journals",
  "GET /api/v1/journals/{journalId}",
  "PATCH /api/v1/journals/{journalId}",
  "DELETE /api/v1/journals/{journalId}",
]);
const actualImplemented = new Set();
const actualPlanned = new Set();
const methods = ["get", "post", "put", "patch", "delete"];

for (const [path, pathItem] of Object.entries(contract.paths ?? {})) {
  const status = pathItem["x-mentalbridge-status"];
  if (status !== "implemented" && status !== "planned") {
    throw new Error(`${path} must declare x-mentalbridge-status`);
  }
  for (const method of methods) {
    if (pathItem[method]) {
      const operation = `${method.toUpperCase()} ${path}`;
      (status === "implemented" ? actualImplemented : actualPlanned).add(
        operation,
      );
      if (status === "implemented") {
        if (
          !setsEqual(
            new Set(Object.keys(pathItem[method].responses ?? {})),
            implementedResponses.get(operation),
          )
        ) {
          throw new Error(
            `${operation} response statuses differ from the implemented boundary`,
          );
        }
        if (
          !Array.isArray(pathItem[method].security) ||
          pathItem[method].security.length !== 0
        ) {
          throw new Error(`${operation} must remain explicitly public`);
        }
      }
    }
  }
}

if (
  !setsEqual(actualImplemented, implementedOperations) ||
  !setsEqual(actualPlanned, plannedOperations)
) {
  throw new Error(
    "Journal contract availability differs from implemented controllers",
  );
}

const schemas = contract.components?.schemas;
const journalEntry = schemas?.JournalEntry;
const journalList = schemas?.JournalListResponse;
const detailContent = journalEntry?.allOf?.[1]?.properties?.content;
const listContent =
  journalList?.properties?.items?.items?.allOf?.[1]?.properties?.content;

if (!detailContent?.required?.includes("text")) {
  throw new Error(
    "Journal detail responses must return authorized full content",
  );
}

if (
  !listContent?.required?.includes("preview") ||
  listContent.required.includes("text")
) {
  throw new Error("Journal list responses must use minimized summaries");
}

console.log(
  "Validated OpenAPI contract: ../contracts/openapi/journal-ai-service-v1.yaml",
);

function setsEqual(left, right) {
  return (
    left.size === right.size && [...left].every((value) => right.has(value))
  );
}
