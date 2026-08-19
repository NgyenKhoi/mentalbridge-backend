import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

describe('Configuration', () => {
  const originalEnv = { ...process.env };

  beforeEach(() => {
    vi.resetModules();
    process.env = { ...originalEnv };
  });

  afterEach(() => {
    process.env = originalEnv;
  });

  async function loadConfig() {
    const mod = await import('../shared/config.js');
    return mod.config;
  }

  it('loads valid configuration', async () => {
    process.env.DB_HOST = 'localhost';
    process.env.DB_PORT = '5432';
    process.env.DB_NAME = 'test_db';
    process.env.DB_USER = 'test_user';
    process.env.DB_PASSWORD = 'test_password';
    process.env.PORT = '3003';
    process.env.NODE_ENV = 'test';

    const config = await loadConfig();

    expect(config.DB_HOST).toBe('localhost');
    expect(config.DB_PORT).toBe(5432);
    expect(config.DB_NAME).toBe('test_db');
    expect(config.PORT).toBe(3003);
    expect(config.NODE_ENV).toBe('test');
  });

  it('applies defaults for optional fields', async () => {
    process.env.DB_HOST = 'localhost';
    process.env.DB_USER = 'test_user';
    process.env.DB_PASSWORD = 'test_password';
    process.env.NODE_ENV = 'test';

    const config = await loadConfig();

    expect(config.DB_PORT).toBe(5432);
    expect(config.DB_NAME).toBe('mentalbridge_content_notification');
    expect(config.DB_POOL_MAX).toBe(10);
    expect(config.LOG_LEVEL).toBe('info');
  });

  it('throws when required DB_USER is missing', async () => {
    process.env.DB_HOST = 'localhost';
    process.env.DB_PASSWORD = 'test_password';
    delete process.env.DB_USER;
    process.env.NODE_ENV = 'test';

    await expect(loadConfig()).rejects.toThrow('Invalid configuration');
  });

  it('throws when required DB_PASSWORD is missing', async () => {
    process.env.DB_HOST = 'localhost';
    process.env.DB_USER = 'test_user';
    delete process.env.DB_PASSWORD;
    process.env.NODE_ENV = 'test';

    await expect(loadConfig()).rejects.toThrow('Invalid configuration');
  });

  it('throws when PORT is out of range', async () => {
    process.env.DB_HOST = 'localhost';
    process.env.DB_USER = 'test_user';
    process.env.DB_PASSWORD = 'pass';
    process.env.PORT = '99999';
    process.env.NODE_ENV = 'test';

    await expect(loadConfig()).rejects.toThrow('Invalid configuration');
  });

  it('throws on invalid NODE_ENV value', async () => {
    process.env.DB_HOST = 'localhost';
    process.env.DB_USER = 'test_user';
    process.env.DB_PASSWORD = 'pass';
    process.env.NODE_ENV = 'staging';

    await expect(loadConfig()).rejects.toThrow('Invalid configuration');
  });

  it('coerces string numbers to integers', async () => {
    process.env.DB_HOST = 'localhost';
    process.env.DB_USER = 'test_user';
    process.env.DB_PASSWORD = 'pass';
    process.env.DB_PORT = '5433';
    process.env.DB_POOL_MAX = '20';
    process.env.NODE_ENV = 'test';

    const config = await loadConfig();

    expect(config.DB_PORT).toBe(5433);
    expect(config.DB_POOL_MAX).toBe(20);
  });
});
