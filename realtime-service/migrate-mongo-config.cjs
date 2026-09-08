const environment = process.env.NODE_ENV ?? 'development';

if (environment === 'development') {
  require('dotenv').config({ override: false, quiet: true });
}

const production = environment === 'production';
const url =
  process.env.REALTIME_MONGODB_URI ?? (production ? undefined : 'mongodb://localhost:27017');
const databaseName =
  process.env.REALTIME_MONGODB_DATABASE ?? (production ? undefined : 'mentalbridge_realtime');

if (!url || !databaseName) {
  throw new Error('Realtime MongoDB migration configuration is required');
}

module.exports = {
  mongodb: { url, databaseName, options: {} },
  migrationsDir: 'migrations',
  changelogCollectionName: 'migrations_changelog',
  migrationFileExtension: '.cjs',
  useFileHash: false,
  moduleSystem: 'commonjs',
};
