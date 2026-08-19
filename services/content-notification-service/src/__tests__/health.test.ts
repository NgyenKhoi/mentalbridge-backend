import request from 'supertest';
import app from '../app';
import * as db from '../infrastructure/database/db';

jest.mock('../infrastructure/database/db');

const mockQuery = db.query as jest.MockedFunction<typeof db.query>;

describe('GET /health/live', () => {
  it('returns 200 ok', async () => {
    const res = await request(app).get('/health/live');
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ status: 'ok' });
  });
});

describe('GET /health/ready', () => {
  it('returns 200 when db is reachable', async () => {
    mockQuery.mockResolvedValueOnce({ rows: [], rowCount: 1, command: 'SELECT', oid: 0, fields: [] });
    const res = await request(app).get('/health/ready');
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('ok');
  });

  it('returns 503 when db is unreachable', async () => {
    mockQuery.mockRejectedValueOnce(new Error('connection refused'));
    const res = await request(app).get('/health/ready');
    expect(res.status).toBe(503);
    expect(res.body.status).toBe('unavailable');
  });
});
