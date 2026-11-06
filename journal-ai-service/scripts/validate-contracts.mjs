import SwaggerParser from "@apidevtools/swagger-parser";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { tsImport } from "tsx/esm/api";

const contractPath = fileURLToPath(
  new URL(
    "../../contracts/openapi/journal-ai-service-v1.yaml",
    import.meta.url,
  ),
);

const contract = await SwaggerParser.validate(contractPath);

const voiceProposalPath = new URL(
  "../../contracts/proposals/companion-voice-v1.openapi.json",
  import.meta.url,
);
const voiceProposal = JSON.parse(await readFile(voiceProposalPath, "utf8"));
const activeVoiceContract = await SwaggerParser.parse(contractPath);
const voiceNames = (value) =>
  JSON.parse(
    JSON.stringify(value)
      .replaceAll(
        "#/components/schemas/Eligibility",
        "#/components/schemas/VoiceEligibility",
      )
      .replaceAll(
        "#/components/schemas/Problem",
        "#/components/schemas/VoiceProblem",
      ),
  );
const sessionVoiceNames = {
  VoiceTurn: "VoiceTurn",
  VoiceReply: "VoiceReply",
  Session: "VoiceSession",
  Connection: "VoiceConnection",
  EmptyCommand: "VoiceEmptyCommand",
  AgentClaim: "VoiceAgentClaim",
  AgentLease: "VoiceAgentLease",
  WorkerSignal: "VoiceWorkerSignal",
  AgentControlResponse: "VoiceAgentControlResponse",
};
const sessionVoiceValue = (value) => {
  let text = JSON.stringify(voiceNames(value));
  for (const [old, current] of Object.entries(sessionVoiceNames))
    text = text.replaceAll(
      `#/components/schemas/${old}`,
      `#/components/schemas/${current}`,
    );
  return JSON.parse(text);
};
for (const [old, current] of Object.entries(sessionVoiceNames)) {
  const actual = structuredClone(
    activeVoiceContract.components.schemas[current],
  );
  if (current === "VoiceSession") {
    assert.deepEqual(actual.properties.contextMode, {
      const: "TEXT_HANDOFF",
      description:
        "Present only after server-bound selected Text context; absence means voice-only.",
    });
    assert.equal(actual.required.includes("contextMode"), false);
    delete actual.properties.contextMode;
  }
  assert.deepEqual(
    actual,
    sessionVoiceValue(voiceProposal.components.schemas[old]),
    `Implemented ${current} must preserve MB-755`,
  );
}
assert.deepEqual(
  activeVoiceContract.components.securitySchemes.VoiceWorkerToken,
  voiceProposal.components.securitySchemes.VoiceWorkerToken,
);
for (const name of ["VoiceEligibility", "VoiceQuota", "VoiceProblem"]) {
  const actual = structuredClone(activeVoiceContract.components.schemas[name]);
  if (name === "VoiceEligibility") {
    assert.equal(
      actual.properties.voiceConsentVersion.const,
      "ai-voice-processing-capstone-v2",
    );
    actual.properties.voiceConsentVersion.const =
      "ai-voice-processing-capstone-v1";
    assert.equal(actual.properties.textContextHandoffEnabled.type, "boolean");
    assert.equal(actual.required.includes("textContextHandoffEnabled"), false);
    delete actual.properties.textContextHandoffEnabled;
  }
  assert.deepEqual(
    actual,
    voiceNames(
      voiceProposal.components.schemas[
        name === "VoiceEligibility"
          ? "Eligibility"
          : name === "VoiceProblem"
            ? "Problem"
            : name
      ],
    ),
    `Implemented ${name} must preserve the approved MB-755 contract`,
  );
}
for (const status of [
  "401",
  "403",
  "404",
  "409",
  "410",
  "422",
  "429",
  "503",
  "504",
]) {
  const name = `VoiceProblem${status}`;
  assert.deepEqual(
    activeVoiceContract.components.responses[name],
    voiceNames(voiceProposal.components.responses[`Problem${status}`]),
    `Implemented ${name} must preserve the approved MB-755 contract`,
  );
}

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
  "POST /api/v1/ai-companion/voice-context-handoffs",
  "POST /internal/v1/ai-companion/voice-sessions/{voiceSessionId}/turns",
  "GET /health/live",
  "GET /health/ready",
  "GET /metrics",
  "GET /api/v1/ai-companion/voice-eligibility",
  "POST /api/v1/ai-companion/voice-sessions",
  "GET /api/v1/ai-companion/voice-sessions/{voiceSessionId}",
  "POST /api/v1/ai-companion/voice-sessions/{voiceSessionId}/connection",
  "POST /api/v1/ai-companion/voice-sessions/{voiceSessionId}/end",
  "POST /internal/v1/ai-companion/voice-sessions/{voiceSessionId}/agent-claim",
  "POST /internal/v1/ai-companion/voice-sessions/{voiceSessionId}/agent-control",
  "POST /api/v1/journals",
  "GET /api/v1/journals",
  "GET /api/v1/journals/{journalId}",
  "GET /api/v1/journals/{journalId}/completion-evidence",
  "PATCH /api/v1/journals/{journalId}",
  "DELETE /api/v1/journals/{journalId}",
  "POST /api/v1/journals/{journalId}/revisions/{revision}/analysis-jobs",
  "GET /api/v1/analysis-jobs/{jobId}",
  "POST /api/v1/longitudinal-analysis-jobs",
  "GET /api/v1/longitudinal-analysis-jobs/{jobId}",
  "GET /internal/v1/users/{userId}/longitudinal-analyses/{analysisId}",
  "POST /internal/v1/support-guide-phrasing",
  "POST /internal/v1/support-plan-companion/messages",
  "POST /internal/v1/consultation-brief-drafts",
  "POST /api/v1/emotion-check-ins",
  "GET /api/v1/emotion-check-ins",
  "GET /api/v1/emotion-check-ins/{localDate}",
  "GET /api/v1/emotion-check-ins/{localDate}/completion-evidence",
  "PATCH /api/v1/emotion-check-ins/{localDate}",
  "DELETE /api/v1/emotion-check-ins/{localDate}",
  "GET /api/v1/emotion-check-in-progress",
  "GET /api/v1/emotion-check-in-context",
  "GET /api/v1/notification-activity",
  "POST /internal/v1/notification-activity",
  "POST /api/v1/ai-companion/conversations",
  "GET /api/v1/ai-companion/conversations",
  "GET /api/v1/ai-companion/conversations/{conversationId}",
  "DELETE /api/v1/ai-companion/conversations/{conversationId}",
  "POST /api/v1/ai-companion/conversations/{conversationId}/messages",
  "PUT /api/v1/ai-companion/conversations/{conversationId}/title",
  "PUT /api/v1/ai-companion/conversations/{conversationId}/context",
]);
const implementedResponses = new Map([
  [
    "POST /api/v1/ai-companion/voice-context-handoffs",
    new Set(["200", "401", "403", "404", "409", "422", "429", "503"]),
  ],
  [
    "POST /internal/v1/ai-companion/voice-sessions/{voiceSessionId}/turns",
    new Set([
      "200",
      "401",
      "403",
      "404",
      "409",
      "410",
      "422",
      "429",
      "503",
      "504",
    ]),
  ],
  [
    "POST /api/v1/ai-companion/voice-sessions",
    new Set(["202", "401", "403", "409", "422", "429", "503"]),
  ],
  [
    "GET /api/v1/ai-companion/voice-sessions/{voiceSessionId}",
    new Set(["200", "401", "403", "404", "503"]),
  ],
  [
    "POST /api/v1/ai-companion/voice-sessions/{voiceSessionId}/connection",
    new Set(["200", "401", "403", "404", "409", "410", "422", "429", "503"]),
  ],
  [
    "POST /api/v1/ai-companion/voice-sessions/{voiceSessionId}/end",
    new Set(["202", "401", "403", "404", "409", "422", "503"]),
  ],
  [
    "POST /internal/v1/ai-companion/voice-sessions/{voiceSessionId}/agent-claim",
    new Set(["200", "401", "403", "404", "409", "410", "422", "503"]),
  ],
  [
    "POST /internal/v1/ai-companion/voice-sessions/{voiceSessionId}/agent-control",
    new Set(["200", "401", "403", "404", "409", "410", "422", "503"]),
  ],
  [
    "GET /api/v1/ai-companion/voice-eligibility",
    new Set(["200", "401", "403", "503"]),
  ],
  ["GET /health/live", new Set(["200"])],
  ["GET /health/ready", new Set(["200", "503"])],
  ["GET /metrics", new Set(["200"])],
  ["POST /api/v1/journals", new Set(["201", "400", "401", "409"])],
  ["GET /api/v1/journals", new Set(["200", "400", "401"])],
  ["GET /api/v1/journals/{journalId}", new Set(["200", "401", "404"])],
  [
    "GET /api/v1/journals/{journalId}/completion-evidence",
    new Set(["200", "400", "401", "503"]),
  ],
  [
    "PATCH /api/v1/journals/{journalId}",
    new Set(["200", "400", "401", "404", "409", "412"]),
  ],
  ["DELETE /api/v1/journals/{journalId}", new Set(["200", "401", "404"])],
  [
    "POST /api/v1/journals/{journalId}/revisions/{revision}/analysis-jobs",
    new Set(["202", "400", "401", "404", "409", "503"]),
  ],
  ["GET /api/v1/analysis-jobs/{jobId}", new Set(["200", "400", "401", "404"])],
  [
    "POST /api/v1/longitudinal-analysis-jobs",
    new Set(["202", "400", "401", "409", "503"]),
  ],
  [
    "GET /api/v1/longitudinal-analysis-jobs/{jobId}",
    new Set(["200", "400", "401", "404"]),
  ],
  [
    "GET /internal/v1/users/{userId}/longitudinal-analyses/{analysisId}",
    new Set(["200", "400", "401", "403", "404", "503"]),
  ],
  [
    "POST /internal/v1/support-guide-phrasing",
    new Set(["201", "400", "401", "403", "503"]),
  ],
  [
    "POST /internal/v1/support-plan-companion/messages",
    new Set(["201", "400", "401"]),
  ],
  [
    "POST /internal/v1/consultation-brief-drafts",
    new Set(["200", "400", "401", "403", "503"]),
  ],
  [
    "POST /api/v1/emotion-check-ins",
    new Set(["201", "400", "401", "409", "503"]),
  ],
  ["GET /api/v1/emotion-check-ins", new Set(["200", "400", "401", "503"])],
  [
    "GET /api/v1/emotion-check-ins/{localDate}",
    new Set(["200", "400", "401", "404", "503"]),
  ],
  [
    "PATCH /api/v1/emotion-check-ins/{localDate}",
    new Set(["200", "400", "401", "404", "409", "412", "503"]),
  ],
  [
    "GET /api/v1/emotion-check-ins/{localDate}/completion-evidence",
    new Set(["200", "400", "401", "503"]),
  ],
  [
    "DELETE /api/v1/emotion-check-ins/{localDate}",
    new Set(["200", "400", "401", "404", "503"]),
  ],
  [
    "GET /api/v1/emotion-check-in-progress",
    new Set(["200", "400", "401", "503"]),
  ],
  [
    "GET /api/v1/emotion-check-in-context",
    new Set(["200", "400", "401", "403", "503"]),
  ],
  ["GET /api/v1/notification-activity", new Set(["200", "400", "401", "503"])],
  [
    "POST /internal/v1/notification-activity",
    new Set(["200", "400", "401", "503"]),
  ],
  ["POST /api/v1/ai-companion/conversations", new Set(["201", "400", "401"])],
  ["GET /api/v1/ai-companion/conversations", new Set(["200", "401"])],
  [
    "GET /api/v1/ai-companion/conversations/{conversationId}",
    new Set(["200", "400", "401", "404"]),
  ],
  [
    "DELETE /api/v1/ai-companion/conversations/{conversationId}",
    new Set(["204", "400", "401", "404"]),
  ],
  [
    "POST /api/v1/ai-companion/conversations/{conversationId}/messages",
    new Set(["201", "400", "401", "403", "404", "409", "429", "503"]),
  ],
  [
    "PUT /api/v1/ai-companion/conversations/{conversationId}/context",
    new Set(["200", "400", "401", "403", "404", "503"]),
  ],
  [
    "PUT /api/v1/ai-companion/conversations/{conversationId}/title",
    new Set(["200", "400", "401", "404", "409", "412", "503"]),
  ],
]);
const plannedOperations = new Set();
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
          operation.startsWith("GET /health/") &&
          (!Array.isArray(pathItem[method].security) ||
            pathItem[method].security.length !== 0)
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
const require = createRequire(import.meta.url);
const {
  exactResultValidator,
  longitudinalResultValidator,
} = require("../migrations/011_bedrock_provider.cjs");
for (const [schemaName, validator] of [
  ["NormalizedAnalysisResult", exactResultValidator],
  ["LongitudinalEvidence", longitudinalResultValidator],
  ["SupportGuidePhrasingResponse", exactResultValidator],
  ["ConsultationBriefAiDraftProviderResponse", exactResultValidator],
]) {
  if (
    !setsEqual(
      new Set(schemas?.[schemaName]?.properties?.provider?.enum ?? []),
      new Set(validator.$jsonSchema.properties.provider.enum),
    )
  ) {
    throw new Error(
      `${schemaName} provider enum differs from migrated runtime providers`,
    );
  }
}
const journalEntry = schemas?.JournalEntry;
const journalList = schemas?.JournalListResponse;
const detailContent = journalEntry?.allOf?.[1]?.properties?.content;
const listContent =
  journalList?.properties?.items?.items?.allOf?.[1]?.properties?.content;
const moods = schemas?.JournalMood?.enum;
const journalText = schemas?.JournalContentInput?.properties?.text;

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

if (
  !Array.isArray(moods) ||
  moods.join(",") !== "GREAT,GOOD,OKAY,LOW,VERY_LOW" ||
  !schemas?.JournalEntryMetadata?.required?.includes("mood")
) {
  throw new Error("Journal mood contract differs from Story 6201");
}

if (journalText?.pattern !== ".*\\S.*") {
  throw new Error("Journal text must reject whitespace-only writes");
}

const checkIn = schemas?.EmotionCheckIn;
const consumerItem = schemas?.EmotionCheckInConsumerItem;
const progress = schemas?.EmotionCheckInProgress;
const notificationActivity = schemas?.NotificationActivity;
const supportPlanCompanionMessage = schemas?.SupportPlanCompanionMessage;
const supportPlanCompanionOperation =
  contract.paths?.["/internal/v1/support-plan-companion/messages"]?.post;
if (
  schemas?.Emotion?.enum?.join(",") !== "GREAT,GOOD,OKAY,LOW,VERY_LOW" ||
  checkIn?.properties?.sourceLabel?.const !== "SELF_REPORTED_EMOTION" ||
  checkIn?.properties?.clinicalUse?.const !==
    "NOT_A_DIAGNOSIS_OR_SAFETY_CLASSIFIER"
) {
  throw new Error("Daily emotion check-in labels differ from the accepted ADR");
}
if (
  schemas?.EmotionInfluenceFactor?.enum?.join(",") !==
    "sleep,work,study,family,friends,health,other" ||
  schemas?.EmotionCheckInValue?.properties?.factors?.maxItems !== 3 ||
  schemas?.EmotionCheckInValue?.properties?.factors?.uniqueItems !== true ||
  !checkIn?.required?.includes("factors")
) {
  throw new Error(
    "Optional emotion influence factors differ from the accepted privacy contract",
  );
}
if (
  notificationActivity?.properties?.interpretation?.const !==
    "FACTUAL_ACTIVITY_NOT_ADHERENCE_OR_RECOVERY" ||
  JSON.stringify(notificationActivity).match(
    /journalText|emotionNote|content|intensity/i,
  )
) {
  throw new Error(
    "Notification activity must remain factual and exclude sensitive content",
  );
}
if (
  consumerItem?.properties?.note ||
  consumerItem?.properties?.factors ||
  consumerItem?.properties?.id
) {
  throw new Error(
    "Consumer context must exclude raw optional context and owner identifiers",
  );
}
if (
  progress?.properties?.interpretation?.const !==
    "FACTUAL_COUNTS_NOT_DIAGNOSIS_OR_RECOVERY" ||
  progress?.properties?.windows?.minItems !== 3 ||
  progress?.properties?.windows?.maxItems !== 3
) {
  throw new Error(
    "Emotion progress must remain factual and expose 7/14/30 windows",
  );
}
if (
  supportPlanCompanionMessage?.additionalProperties !== false ||
  supportPlanCompanionMessage?.properties?.schemaVersion?.const !==
    "support-plan-companion-message-v1" ||
  supportPlanCompanionMessage?.properties?.delivery?.enum?.join(",") !==
    "GENERATED,TEMPLATE" ||
  supportPlanCompanionMessage?.properties?.text?.maxLength !== 480 ||
  supportPlanCompanionMessage?.properties?.model?.maxLength !== 2_048 ||
  supportPlanCompanionMessage?.properties?.command ||
  supportPlanCompanionMessage?.properties?.mutation
) {
  throw new Error(
    "SupportPlan Companion response must remain closed, versioned, and mutation-free",
  );
}
if (
  !supportPlanCompanionOperation?.security?.some(
    (requirement) =>
      Object.hasOwn(requirement, "bearerAuth") &&
      Object.hasOwn(requirement, "supportPlanCareServiceToken"),
  ) ||
  contract.components?.securitySchemes?.supportPlanCareServiceToken?.in !==
    "header" ||
  contract.components?.securitySchemes?.supportPlanCareServiceToken?.name !==
    "x-mentalbridge-service-token"
) {
  throw new Error(
    "SupportPlan Companion must require both end-user bearer and the dedicated Care service token",
  );
}

const careContractPath = fileURLToPath(
  new URL("../../contracts/openapi/care-service-v1.yaml", import.meta.url),
);
const care = await SwaggerParser.validate(careContractPath);
const careSchemas = care.components.schemas;
assert.equal(care.info.version, "1.12.0");
assert.equal(contract.info.version, "1.19.0");
assert.deepEqual(careSchemas.ConsentType.enum, [
  "PRIVACY_POLICY",
  "AI_PROCESSING",
  "AI_CONTEXT_PROCESSING",
]);
assert.equal(careSchemas.ConsentCollection.properties.decisions.maxItems, 3);
assert.equal(
  careSchemas.AiProcessingDisclosure.properties.version.const,
  "ai-processing-capstone-v2",
);
for (const name of [
  "AiConsentAuthorizations",
  "AiContextAuthorization",
  "AiContextConsentDecisionRequest",
  "AiContextProcessingDisclosure",
]) {
  assert.equal(careSchemas[name].additionalProperties, false, name);
  assert.deepEqual(
    new Set(careSchemas[name].required),
    new Set(Object.keys(careSchemas[name].properties)),
    name,
  );
}
assert.equal(
  careSchemas.AiContextAuthorization.properties.consentType.const,
  "AI_CONTEXT_PROCESSING",
);
assert.deepEqual(careSchemas.AiContextAuthorization.properties.reason.enum, [
  "GRANTED",
  "MISSING",
  "REVOKED",
  "POLICY_OUTDATED",
  "DEPENDENCY_REQUIRED",
]);
const snapshotOperation = care.paths["/api/v1/consents/ai-authorizations"].get;
assert.deepEqual(snapshotOperation.security, [{ bearerAuth: [] }]);
assert.deepEqual(Object.keys(snapshotOperation.responses), [
  "200",
  "401",
  "403",
  "503",
]);
for (const response of Object.values(snapshotOperation.responses)) {
  assert.equal(response.headers["Cache-Control"].schema.const, "no-store");
}
const { parseAiConsentAuthorizations, ConsentUnavailableError } =
  await tsImport("../src/analysis/analysis.ts", import.meta.url);
const snapshotExamples =
  snapshotOperation.responses["200"].content["application/json"].examples;
assert.deepEqual(Object.keys(snapshotExamples), [
  "missingBoth",
  "l1Only",
  "bothGranted",
  "l2Suspended",
]);
for (const [name, { value }] of Object.entries(snapshotExamples)) {
  assert.deepEqual(parseAiConsentAuthorizations(value), value, name);
}
const invalidSnapshots = [
  (value) => {
    value.context.authorized = true;
    value.context.reason = "MISSING";
  },
  (value) => {
    value.context.policyVersion = "ai-context-processing-capstone-v0";
  },
  (value) => {
    value.chat.policyVersion = "ai-processing-capstone-v1";
  },
  (value) => {
    value.context.consentType = "AI_PROCESSING";
  },
  (value) => {
    value.chat.authorized = false;
    value.chat.reason = "REVOKED";
  },
  (value) => {
    value.context.authorized = false;
    value.context.reason = "DEPENDENCY_REQUIRED";
  },
  (value) => {
    value.context.reason = "UNKNOWN";
  },
  (value) => {
    value.checkedAt = "yesterday";
  },
  (value) => {
    value.context.extraGrant = true;
  },
];
for (const mutate of invalidSnapshots) {
  const invalid = structuredClone(snapshotExamples.bothGranted.value);
  mutate(invalid);
  assert.throws(
    () => parseAiConsentAuthorizations(invalid),
    ConsentUnavailableError,
  );
}
const noticeOperation =
  care.paths["/api/v1/ai-context-processing-disclosures/current"].get;
assert.equal(noticeOperation.security, undefined);
const notice =
  noticeOperation.responses["200"].content["application/json"].examples
    .currentVietnameseNotice.value;
for (const key of [
  "consentType",
  "version",
  "locale",
  "title",
  "capstoneOnly",
]) {
  assert.equal(
    notice[key],
    careSchemas.AiContextProcessingDisclosure.properties[key].const,
  );
}
assert.ok(notice.content.length <= 4000);
assert.equal(
  schemas.EmotionCheckInConsumerContext.properties.consent.properties.type
    .const,
  "AI_CONTEXT_PROCESSING",
);
const contextSelection =
  contract.paths["/api/v1/ai-companion/conversations/{conversationId}/context"]
    .put;
assert.match(
  contextSelection.responses["403"].description,
  /AI_CONTEXT_CONSENT_REQUIRED/,
);
assert.match(
  contextSelection.responses["503"].description,
  /CONSENT_UNAVAILABLE/,
);
const Ajv2020 = createRequire(require.resolve("@apidevtools/swagger-parser"))(
  "ajv/dist/2020.js",
).default;
const { z } = require("zod");
const exampleValidator = new Ajv2020({
  strict: true,
  allErrors: true,
  unicodeRegExp: true,
});
exampleValidator.addFormat(
  "uuid",
  /^[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}$/i,
);
exampleValidator.addFormat(
  "date-time",
  (value) => z.string().datetime({ offset: true }).safeParse(value).success,
);
exampleValidator.addFormat(
  "uri-reference",
  (value) =>
    !/[\s<>]/u.test(value) &&
    URL.canParse(value, "https://mentalbridge.invalid"),
);
const chatOperation =
  contract.paths["/api/v1/ai-companion/conversations/{conversationId}/messages"]
    .post;
const chatResponse = chatOperation.responses["201"].content["application/json"];
assert.deepEqual(Object.keys(chatResponse.examples), [
  "l1OnlyPlainChat",
  "bothLevelsActualContext",
  "firstReplyTopicTitle",
  "invalidTitleFirstMessageFallback",
  "retainedNeutralDatedTitle",
]);
const validateChatResponse = exampleValidator.compile(chatResponse.schema);
for (const [name, { value }] of Object.entries(chatResponse.examples)) {
  assert.equal(
    validateChatResponse(value),
    true,
    `${name}: ${JSON.stringify(validateChatResponse.errors)}`,
  );
}
assert.deepEqual(chatResponse.examples.l1OnlyPlainChat.value.contextKinds, []);
assert.deepEqual(
  chatResponse.examples.bothLevelsActualContext.value.contextKinds,
  ["JOURNAL"],
);
const titleTokenPattern = "^[A-Za-z0-9_-]{43}$";
const quotedTitleTokenPattern = '^"[A-Za-z0-9_-]{43}"$';
assert.deepEqual(schemas.AiConversationTitleVersion.type, "string");
assert.equal(schemas.AiConversationTitleVersion.pattern, titleTokenPattern);
for (const schemaName of ["AiConversation", "AiConversationSummary"]) {
  assert.deepEqual(
    schemas[schemaName].properties.titleVersion,
    schemas.AiConversationTitleVersion,
  );
  assert.equal(schemas[schemaName].required.includes("titleVersion"), false);
}
for (const field of ["title", "titleVersion"]) {
  assert.equal(schemas.SendAiMessageResponse.required.includes(field), false);
  assert.ok(schemas.SendAiMessageResponse.properties[field]);
  assert.equal(
    Object.hasOwn(chatResponse.examples.l1OnlyPlainChat.value, field),
    false,
  );
}
assert.equal(
  chatResponse.examples.firstReplyTopicTitle.value.title,
  "Áp lực công việc",
);
const fallbackSource =
  "Hôm nay tôi muốn kể về công việc và những điều đang khiến tôi lo lắng.";
assert.equal(
  chatResponse.examples.invalidTitleFirstMessageFallback.value.title,
  [...fallbackSource].slice(0, 40).join(""),
);
assert.equal(
  chatResponse.examples.retainedNeutralDatedTitle.value.title,
  "Trò chuyện 2026-11-06",
);
assert.match(chatOperation.description, /deferred to MB-810-CARE-SAFETY-TITLE/);
const renameOperation =
  contract.paths["/api/v1/ai-companion/conversations/{conversationId}/title"]
    .put;
assert.equal(renameOperation.operationId, "renameAiCompanionConversation");
assert.deepEqual(renameOperation.security, [{ bearerAuth: [] }]);
assert.deepEqual(
  renameOperation.parameters.map(({ name, in: location, required }) => ({
    name,
    in: location,
    required,
  })),
  [
    { name: "conversationId", in: "path", required: true },
    { name: "Idempotency-Key", in: "header", required: true },
    { name: "If-Match", in: "header", required: true },
    { name: "x-correlation-id", in: "header", required: false },
  ],
);
const titlePrecondition =
  contract.components.parameters.IfMatchAiConversationTitle;
assert.equal(titlePrecondition.schema.pattern, quotedTitleTokenPattern);
const validTitlePrecondition = '"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"';
assert.match(validTitlePrecondition, new RegExp(quotedTitleTokenPattern));
for (const invalid of [
  "*",
  `W/${validTitlePrecondition}`,
  `${validTitlePrecondition}, ${validTitlePrecondition}`,
  "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  '"short"',
]) {
  assert.doesNotMatch(invalid, new RegExp(quotedTitleTokenPattern));
}
assert.equal(
  renameOperation.responses["200"].headers.ETag.schema.pattern,
  quotedTitleTokenPattern,
);
assert.equal(
  renameOperation.responses["200"].headers["Cache-Control"].schema.const,
  "no-store",
);
assert.equal(
  schemas.RenameAiConversationTitleRequest.additionalProperties,
  false,
);
assert.deepEqual(schemas.RenameAiConversationTitleRequest.required, ["title"]);
assert.deepEqual(
  Object.keys(schemas.RenameAiConversationTitleRequest.properties),
  ["title"],
);
assert.equal(schemas.AiConversationTitle.additionalProperties, false);
assert.deepEqual(schemas.AiConversationTitle.required, [
  "conversationId",
  "title",
  "titleVersion",
]);
assert.deepEqual(Object.keys(schemas.AiConversationTitle.properties), [
  "conversationId",
  "title",
  "titleVersion",
]);
for (const titleSchema of [
  schemas.CreateAiConversationRequest.properties.title,
  schemas.RenameAiConversationTitleRequest.properties.title,
]) {
  assert.equal(titleSchema.minLength, 1);
  assert.equal(titleSchema.maxLength, undefined);
  assert.equal(titleSchema.pattern, "^\\s*(?:\\S|\\S[\\s\\S]{0,78}\\S)\\s*$");
  assert.match(titleSchema.description, /Unicode code points after outer trim/);
}
const validateRename = exampleValidator.compile(
  schemas.RenameAiConversationTitleRequest,
);
const validateCreate = exampleValidator.compile(
  schemas.CreateAiConversationRequest,
);
const paddedTitle = (count) => ` \n${"😀".repeat(count)}\t `;
for (const { value } of Object.values(
  renameOperation.requestBody.content["application/json"].examples,
)) {
  assert.equal(
    validateRename(value),
    true,
    JSON.stringify(validateRename.errors),
  );
}
for (const title of [
  "Một hai ba bốn năm sáu bảy tám",
  "😀".repeat(80),
  paddedTitle(80),
]) {
  assert.equal(
    validateRename({ title }),
    true,
    JSON.stringify(validateRename.errors),
  );
  assert.equal(
    validateCreate({ title }),
    true,
    JSON.stringify(validateCreate.errors),
  );
}
for (const title of ["😀".repeat(81), paddedTitle(81), "   "]) {
  assert.equal(validateCreate({ title }), false);
}
for (const value of [
  {},
  { title: "" },
  { title: "   " },
  { title: null },
  { title: "😀".repeat(81) },
  { title: paddedTitle(81) },
  { title: "Hôm nay", ownerAccountId: "other" },
]) {
  assert.equal(validateRename(value), false);
}
const titleErrorCodes = {
  400: "VALIDATION_FAILED",
  401: "AUTHENTICATION_REQUIRED",
  404: "RESOURCE_NOT_FOUND",
  409: "IDEMPOTENCY_KEY_REUSED",
  412: "CHAT_TITLE_VERSION_CONFLICT",
  503: "CHAT_TITLE_UNAVAILABLE",
};
let titleExampleCount = 0;
for (const [status, response] of Object.entries(renameOperation.responses)) {
  const mediaType =
    status === "200" ? "application/json" : "application/problem+json";
  const media = response.content[mediaType];
  const validate = exampleValidator.compile(media.schema);
  for (const [name, { value }] of Object.entries(media.examples)) {
    assert.equal(
      validate(value),
      true,
      `${name}: ${JSON.stringify(validate.errors)}`,
    );
    if (status !== "200") {
      assert.equal(value.status, Number(status));
      assert.equal(value.code, titleErrorCodes[status]);
      assert.match(response.description, new RegExp(titleErrorCodes[status]));
    }
    titleExampleCount += 1;
  }
}
const renameExamples =
  renameOperation.responses["200"].content["application/json"].examples;
assert.deepEqual(
  renameExamples.renamedTitle.value,
  renameExamples.identicalReplay.value,
);
assert.equal(
  renameOperation.responses["200"].headers.ETag.example,
  `"${renameExamples.renamedTitle.value.titleVersion}"`,
);
assert.deepEqual(
  renameOperation.responses["404"].content["application/problem+json"].examples
    .foreignConversation.value,
  renameOperation.responses["404"].content["application/problem+json"].examples
    .missingConversation.value,
);
for (const [path, status] of [
  ["/api/v1/ai-companion/conversations", "200"],
  ["/api/v1/ai-companion/conversations/{conversationId}", "200"],
]) {
  const media =
    contract.paths[path].get.responses[status].content["application/json"];
  const validate = exampleValidator.compile(media.schema);
  for (const [name, { value }] of Object.entries(media.examples)) {
    assert.equal(
      validate(value),
      true,
      `${name}: ${JSON.stringify(validate.errors)}`,
    );
    titleExampleCount += 1;
  }
}
console.log(
  `Validated MB-810 title contract: ${titleExampleCount} rename/legacy examples, 5 send examples, optional metadata compatibility, Unicode bounds and strong title preconditions`,
);
const chatUnavailable =
  chatOperation.responses["503"].content["application/problem+json"];
const validateChatUnavailable = exampleValidator.compile(
  chatUnavailable.schema,
);
const careTimeout = chatUnavailable.examples.careTimeout.value;
assert.equal(
  validateChatUnavailable(careTimeout),
  true,
  `careTimeout: ${JSON.stringify(validateChatUnavailable.errors)}`,
);
assert.equal(careTimeout.status, 503);
assert.equal(careTimeout.code, "CONSENT_OR_ENTITLEMENT_UNAVAILABLE");
const consentCollection =
  care.paths["/api/v1/consents"].get.responses["200"].content[
    "application/json"
  ];
const validateConsentCollection = exampleValidator.compile(
  consentCollection.schema,
);
const newAccountConsents = consentCollection.examples.newAccount.value;
assert.equal(
  validateConsentCollection(newAccountConsents),
  true,
  `newAccount: ${JSON.stringify(validateConsentCollection.errors)}`,
);
assert.deepEqual(newAccountConsents, { decisions: [] });
console.log(
  `Validated MB-806 Care-to-Journal binding: ${Object.keys(snapshotExamples).length} snapshot examples, ${invalidSnapshots.length} fail-closed mutations, 4 full response examples, immutable L1 and new L2 disclosure`,
);

console.log(
  "Validated OpenAPI contract: ../contracts/openapi/journal-ai-service-v1.yaml",
);

function setsEqual(left, right) {
  return (
    left.size === right.size && [...left].every((value) => right.has(value))
  );
}
