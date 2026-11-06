import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { enterprisePatch } from './config.mjs';

const mutable = new Set(['agent-default-model', 'llm-deepseek', 'llm-pi-ai',
  'ui-settings-models', 'ui-model-selection', 'session-title-llm', 'ui-chat']);

/** Native profile overrides must sit above deployment defaults to remain editable. */
export async function prepareProfile(config, home, executionRoot) {
  const profile = resolve(home, 'profiles/web');
  const bundleName = '@jsd-aird/enterprise-defaults';
  const bundle = resolve(profile, 'node_modules', bundleName);
  await mkdir(bundle, { recursive: true });
  const patches = enterprisePatch({ ...config, executionRoot });
  await writeFile(resolve(bundle, 'package.json'), JSON.stringify({
    name: bundleName, version: '1.0.0', private: true,
    engines: { dsh: '0.2.2-rc.1' }, dsh: { bundle: { patch: './defaults.patch.json' } },
  }, null, 2));
  await writeFile(resolve(bundle, 'defaults.patch.json'), JSON.stringify(patches.filter(row => mutable.has(row.id)), null, 2));
  const manifestPath = resolve(profile, 'package.json');
  let manifest;
  try { manifest = JSON.parse(await readFile(manifestPath, 'utf8')); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  manifest ??= { name: 'dsh-profile-web', private: true, dependencies: {},
    dsh: { profile: { bundles: ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app'] } } };
  const bundles = manifest.dsh.profile.bundles;
  if (!bundles.includes(bundleName)) bundles.push(bundleName);
  await writeFile(manifestPath, JSON.stringify(manifest, null, 2));
  // Authority and execution boundaries stay in the final deployment layer.
  const policy = resolve(home, 'enterprise.patch.json');
  await writeFile(policy, JSON.stringify(patches.filter(row => !mutable.has(row.id)), null, 2));
  return policy;
}
