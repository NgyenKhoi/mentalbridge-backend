if (process.env.NODE_ENV !== "production") {
  require("dotenv").config({ override: false, quiet: true });
}

const isProduction = process.env.NODE_ENV === "production";
const url =
  process.env.JOURNAL_AI_MONGODB_URI ??
  (isProduction ? undefined : "mongodb://localhost:27017");
const databaseName =
  process.env.JOURNAL_AI_MONGODB_DATABASE ??
  (isProduction ? undefined : "mentalbridge_journal_ai");

if (!url || !databaseName) {
  throw new Error("MongoDB migration configuration is required");
}

module.exports = {
  mongodb: {
    url,
    databaseName,
    options: {},
  },
  migrationsDir: "migrations",
  changelogCollectionName: "migrations_changelog",
  migrationFileExtension: ".cjs",
  useFileHash: false,
  moduleSystem: "commonjs",
};
