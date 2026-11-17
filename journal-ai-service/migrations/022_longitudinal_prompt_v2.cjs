const longitudinal = require("./009_longitudinal_context_analysis.cjs");
const bedrock = require("./011_bedrock_provider.cjs");

const promptVersions = ["longitudinal-v1", "longitudinal-v2"];

const longitudinalJobValidator = structuredClone(
  bedrock.longitudinalJobValidator,
);
longitudinalJobValidator.$jsonSchema.properties.route.properties.promptVersion =
  { enum: promptVersions };

const longitudinalResultValidator = structuredClone(
  bedrock.longitudinalResultValidator,
);
longitudinalResultValidator.$jsonSchema.properties.promptVersion = {
  enum: promptVersions,
};

const applyValidator = (db, collection, validator) =>
  db.command({
    collMod: collection,
    validator,
    validationLevel: "strict",
    validationAction: "error",
  });

module.exports = {
  async up(db) {
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
  },

  async down(db) {
    const v2Documents = await Promise.all([
      db.collection(longitudinal.jobCollection).countDocuments({
        "route.promptVersion": "longitudinal-v2",
      }),
      db
        .collection(longitudinal.resultCollection)
        .countDocuments({ promptVersion: "longitudinal-v2" }),
    ]);
    if (v2Documents.some((count) => count > 0))
      throw new Error(
        "Cannot remove longitudinal-v2 validation after v2 provenance has been written",
      );

    await applyValidator(
      db,
      longitudinal.jobCollection,
      bedrock.longitudinalJobValidator,
    );
    await applyValidator(
      db,
      longitudinal.resultCollection,
      bedrock.longitudinalResultValidator,
    );
  },

  longitudinalJobValidator,
  longitudinalResultValidator,
};
