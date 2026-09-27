const routing = require("./007_entitlement_aware_model_routing.cjs");
const benchmark = require("./008_ai_benchmark_metadata.cjs");
const longitudinal = require("./009_longitudinal_context_analysis.cjs");
const companion = require("./010_ai_companion_chat_quotas.cjs");

const allProviders = ["DETERMINISTIC_FAKE", "GEMINI", "OPENAI", "BEDROCK"];
const realProviders = ["GEMINI", "OPENAI", "BEDROCK"];

const exactJobValidator = structuredClone(routing.jobValidator);
exactJobValidator.$jsonSchema.properties.route.properties.provider.enum =
  allProviders;

const exactResultValidator = structuredClone(routing.resultValidator);
exactResultValidator.$jsonSchema.properties.provider.enum = allProviders;

const benchmarkRunValidator = structuredClone(benchmark.runValidator);
benchmarkRunValidator.$jsonSchema.properties.candidates.items.properties.provider.enum =
  realProviders;
benchmarkRunValidator.$jsonSchema.properties.candidates.maxItems = 3;
benchmarkRunValidator.$jsonSchema.properties.aggregates.items.properties.provider.enum =
  realProviders;
benchmarkRunValidator.$jsonSchema.properties.aggregates.maxItems = 3;

const benchmarkCaseResultValidator = structuredClone(
  benchmark.caseResultValidator,
);
benchmarkCaseResultValidator.$jsonSchema.properties.provider.enum =
  realProviders;

const longitudinalJobValidator = structuredClone(longitudinal.jobValidator);
longitudinalJobValidator.$jsonSchema.properties.route.properties.provider.enum =
  allProviders;

const longitudinalResultValidator = structuredClone(
  longitudinal.resultValidator,
);
longitudinalResultValidator.$jsonSchema.properties.provider.enum = allProviders;

const companionConversationValidator = structuredClone(
  companion.conversationValidator,
);
companionConversationValidator.$jsonSchema.properties.messages.items.properties.route.properties.provider.enum =
  allProviders;

const applyValidator = (db, collection, validator) =>
  db.command({
    collMod: collection,
    validator,
    validationLevel: "strict",
    validationAction: "error",
  });

module.exports = {
  async up(db) {
    await applyValidator(db, "analysis_jobs", exactJobValidator);
    await applyValidator(db, "journal_analysis_results", exactResultValidator);
    await applyValidator(db, benchmark.runCollection, benchmarkRunValidator);
    await applyValidator(
      db,
      benchmark.caseResultCollection,
      benchmarkCaseResultValidator,
    );
    await applyValidator(
      db,
      longitudinal.jobCollection,
      longitudinalJobValidator,
    );
    await applyValidator(
      db,
      longitudinal.resultCollection,
      longitudinalResultValidator,
    );
    await applyValidator(
      db,
      companion.conversationCollection,
      companionConversationValidator,
    );
  },

  async down(db) {
    const bedrockDocuments = await Promise.all([
      db.collection("analysis_jobs").countDocuments({
        "route.provider": "BEDROCK",
      }),
      db
        .collection("journal_analysis_results")
        .countDocuments({ provider: "BEDROCK" }),
      db.collection(benchmark.runCollection).countDocuments({
        $or: [
          { "candidates.provider": "BEDROCK" },
          { "aggregates.provider": "BEDROCK" },
        ],
      }),
      db
        .collection(benchmark.caseResultCollection)
        .countDocuments({ provider: "BEDROCK" }),
      db.collection(longitudinal.jobCollection).countDocuments({
        "route.provider": "BEDROCK",
      }),
      db
        .collection(longitudinal.resultCollection)
        .countDocuments({ provider: "BEDROCK" }),
      db.collection(companion.conversationCollection).countDocuments({
        "messages.route.provider": "BEDROCK",
      }),
    ]);
    if (bedrockDocuments.some((count) => count > 0))
      throw new Error(
        "Cannot remove Bedrock provider validation after Bedrock provenance has been written",
      );

    await applyValidator(db, "analysis_jobs", routing.jobValidator);
    await applyValidator(
      db,
      "journal_analysis_results",
      routing.resultValidator,
    );
    await applyValidator(db, benchmark.runCollection, benchmark.runValidator);
    await applyValidator(
      db,
      benchmark.caseResultCollection,
      benchmark.caseResultValidator,
    );
    await applyValidator(
      db,
      longitudinal.jobCollection,
      longitudinal.jobValidator,
    );
    await applyValidator(
      db,
      longitudinal.resultCollection,
      longitudinal.resultValidator,
    );
    await applyValidator(
      db,
      companion.conversationCollection,
      companion.conversationValidator,
    );
  },

  exactJobValidator,
  exactResultValidator,
  benchmarkRunValidator,
  benchmarkCaseResultValidator,
  longitudinalJobValidator,
  longitudinalResultValidator,
  companionConversationValidator,
};
