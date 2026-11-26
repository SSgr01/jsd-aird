import test from 'node:test';
import assert from 'node:assert/strict';
import { hostEnvironment } from '../src/host-environment.mjs';

test('local-provider credentials remain separate from the official provider and business secrets', () => {
  const result = hostEnvironment({
    PATH: 'runtime-bin', JSD_AIRD_DSH_LOCAL_MODEL_API_KEY: 'synthetic-local-key',
    JSD_AIRD_MODEL_API_KEY: 'synthetic-business-key', DATABASE_PASSWORD: 'synthetic-db-password',
  });
  assert.equal(result.JSD_AIRD_DSH_LOCAL_MODEL_API_KEY, 'synthetic-local-key');
  assert.equal(result.PATH, 'runtime-bin');
  assert.equal(result.JSD_AIRD_DSH_API_KEY, undefined);
  assert.equal(result.JSD_AIRD_MODEL_API_KEY, undefined);
  assert.equal(result.DATABASE_PASSWORD, undefined);
});

test('separately configured official and local credentials are preserved without substitution', () => {
  const result = hostEnvironment({ JSD_AIRD_DSH_API_KEY: 'official', JSD_AIRD_DSH_LOCAL_MODEL_API_KEY: 'local' });
  assert.equal(result.JSD_AIRD_DSH_API_KEY, 'official');
  assert.equal(result.JSD_AIRD_DSH_LOCAL_MODEL_API_KEY, 'local');
});
