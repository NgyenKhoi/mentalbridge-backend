import { describe, expect, it } from 'vitest';

import { loadConfiguration } from '../configuration/configuration.js';

const requiredEnvironment = {
  NODE_ENV: 'test',
  DB_HOST: 'localhost',
  DB_USER: 'test_user',
  DB_PASSWORD: 'test_password',
} satisfies NodeJS.ProcessEnv;

describe('configuration', () => {
  it('loads required values and defaults', () => {
    const configuration = loadConfiguration(requiredEnvironment);

    expect(configuration.DB_PORT).toBe(5432);
    expect(configuration.DB_NAME).toBe('mentalbridge_content_notification');
    expect(configuration.DB_POOL_MAX).toBe(10);
    expect(configuration.PORT).toBe(3003);
    expect(configuration.LOG_LEVEL).toBe('info');
  });

  it('normalizes configured CORS origins', () => {
    const configuration = loadConfiguration({
      ...requiredEnvironment,
      CORS_ORIGINS: 'https://admin.example, https://app.example',
    });

    expect(configuration.ALLOWED_ORIGINS).toEqual(['https://admin.example', 'https://app.example']);
  });

  it.each(['DB_HOST', 'DB_USER', 'DB_PASSWORD'] as const)('rejects a missing %s', (key) => {
    const environment: NodeJS.ProcessEnv = { ...requiredEnvironment };
    delete environment[key];

    expect(() => loadConfiguration(environment)).toThrow();
  });

  it('rejects an invalid port', () => {
    expect(() => loadConfiguration({ ...requiredEnvironment, PORT: '70000' })).toThrow();
  });
});
