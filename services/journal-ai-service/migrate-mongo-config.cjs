require("dotenv").config({ override: false, quiet: true });

const url = process.env.MONGODB_URI ?? "mongodb://localhost:27017";
const databaseName = process.env.MONGODB_DATABASE ?? "mentalbridge_journal_ai";

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
