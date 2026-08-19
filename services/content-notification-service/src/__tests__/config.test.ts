import { describe, it, expect, beforeEach, afterEach } from '@jest/globals';

describe('Configuration', () => {
  const originalEnv = process.env;

  beforeEach(() => {
    jest.resetModules();
    process.env = { ...originalEnv };
  });

  afterEach(() => {
    process.env = originalEnv;
  });

  it('should load valid configuration', () => {
    process.env.DB_HOST = 'localhost';
    process.env.DB_PORT = '5432';
    process.env.DB_NAME = 'test_db';
    process.env.DB_USER = 'test_user';
    process.env.DB_PASSWORD = 'test_password';
    process.env.PORT = '3003';
    process.env.NODE_ENV = 'test';

    const { config } = require('../shared/config');

    expect(config.DB_HOST).toBe('localhost');
    expect(config.DB_PORT).toBe(5432);
    expect(config.DB_NAME).toBe('test_db');
    expect(config.PORT).toBe(3003);
    expect(config.NODE_ENV).toBe('test');
  });

  it('should apply defaults for optional fields', () => {
    process.env.DB_HOST = 'localhost';
    process.env.DB_USER = 'test_user';
    process.env.DB_PASSWORD = 'test_password';
    process.env.NODE_ENV = 'development';

    const { config } = require('../shared/config');

    expect(config.DB_PORT).toBe(5432);
    expect(config.DB_NAME).toBe('mentalbridge_content_notification');
    expect(config.DB_POOL_MAX).toBe(10);
    expect(config.DB_IDLE_TIMEOUT_MS).toBe(30000);
    expect(config.NODE_ENV).toBe('development');
  });

  it('should throw error when required fields are missing', () => {
    process.env.DB_HOST = 'localhost';
    // Missing DB_USER and DB_PASSWORD

    expect(() => {
      jest.isolateModules(() => {
        require('../shared/config');
      });
    }).toThrow('Invalid configuration');
  });

  it('should validate port range', () => {
    process.env.DB_HOST = 'localhost';
    process.env.DB_USER = 'test_user';
    process.env.DB_PASSWORD = 'test_password';
    process.env.PORT = '99999'; // Invalid port

    expect(() => {
      jest.isolateModules(() => {
        require('../shared/config');
      });
    }).toThrow('Invalid configuration');
  });

  it('should validate NODE_ENV enum', () => {
    process.env.DB_HOST = 'localhost';
    process.env.DB_USER = 'test_user';
    process.env.DB_PASSWORD = 'test_password';
    process.env.NODE_ENV = 'invalid_env';

    expect(() => {
      jest.isolateModules(() => {
        require('../shared/config');
      });
    }).toThrow('Invalid configuration');
  });

  it('should coerce string numbers to integers', () => {
    process.env.DB_HOST = 'localhost';
    process.env.DB_USER = 'test_user';
    process.env.DB_PASSWORD = 'test_password';
    process.env.DB_PORT = '5433';
    process.env.DB_POOL_MAX = '20';

    const { config } = require('../shared/config');

    expect(typeof config.DB_PORT).toBe('number');
    expect(config.DB_PORT).toBe(5433);
    expect(typeof config.DB_POOL_MAX).toBe('number');
    expect(config.DB_POOL_MAX).toBe(20);
  });
});
