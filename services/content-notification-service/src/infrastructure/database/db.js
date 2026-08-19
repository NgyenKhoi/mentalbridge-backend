const { Pool } = require('pg');

const pool = new Pool({
  host: process.env.CONTENT_DB_HOST || 'localhost',
  port: parseInt(process.env.CONTENT_DB_PORT || '5432', 10),
  database: process.env.CONTENT_DB_NAME || 'mentalbridge',
  user: process.env.CONTENT_DB_USER || 'content_svc',
  password: process.env.CONTENT_DB_PASSWORD,
  max: 10,
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 2000,
});

module.exports = {
  query: (text, params) => pool.query(text, params),
  pool,
};
