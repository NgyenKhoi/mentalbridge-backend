import { describe, it, expect, vi, beforeAll } from 'vitest';
import request from 'supertest';

// Set required env vars before importing app
process.env.DB_HOST = 'localhost';
process.env.DB_PORT = '5432';
process.env.DB_NAME = 'test_db';
process.env.DB_USER = 'test_user';
process.env.DB_PASSWORD = 'test_password';
process.env.NODE_ENV = 'test';

vi.mock('../infrastructure/database/db.js', () => ({
  query: vi.fn(),
  pool: { end: vi.fn() },
}));

const { default: app } = await import('../app.js');
const { query: mockQuery } = await import('../infrastructure/database/db.js');

describe('GET /health/live', () => {
  it('returns 200 and does not depend on DB', async () => {
    const res = await request(app).get('/health/live');
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ status: 'ok' });
  });
});

describe('GET /health/ready', () => {
  it('returns 200 when DB is reachable', async () => {
    vi.mocked(mockQuery).mockResolvedValueOnce({
      rows: [],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    } as never);

    const res = await request(app).get('/health/ready');
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ status: 'ok', db: 'connected' });
  });

  it('returns 503 when DB is unreachable', async () => {
    vi.mocked(mockQuery).mockRejectedValueOnce(new Error('connection refused'));

    const res = await request(app).get('/health/ready');
    expect(res.status).toBe(503);
    expect(res.body).toEqual({ status: 'unavailable', db: 'disconnected' });
  });
});
