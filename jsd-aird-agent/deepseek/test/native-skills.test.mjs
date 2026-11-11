import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { readFile } from 'node:fs/promises';
import { apply } from '../../enterprise-plugin/src/skill-runtime.mjs';
import { configuration, standardPlugins } from '../src/config.mjs';
import { Context } from '../../../vendor/deepseek-harness/vendor/cordis/lib/index.js';
import Loader from '../../../vendor/deepseek-harness/vendor/loader/lib/index.js';
import SkillRegistry from '../../../vendor/deepseek-harness/packages/skill/skill/lib/index.js';

test('enterprise skill entry mounts through the native plugin loader', async t => {
  const ctx = new Context();
  t.after(() => ctx.fiber.dispose());
  ctx.baseUrl = new URL('../../../vendor/deepseek-harness/apps/cli/', import.meta.url).href;
  await ctx.plugin(Loader);
  await ctx.plugin(SkillRegistry);
  const config = configuration({JSD_AIRD_HARNESS_RUNTIME_SECRET:'x'.repeat(32)});
  const entry = standardPlugins(config).find(row => row.id === 'skill-filesystem');
  await ctx.loader.root.update([entry]);
  const mounted = [...ctx.loader.entries()].find(row => row.options.id === entry.id);
  assert.ok(mounted.fiber, 'native loader must activate the configured module');
  await mounted.fiber.await();
  const skills = await ctx.skills.list({});
  assert.equal(skills.length, 4);
  for (const skill of skills) assert.equal(skill.resourceBase.kind, 'opaque');
});

test('enterprise guidance uses native skill discovery without exposing inaccessible host resources', async () => {
  let provider;
  const lifecycle = [];
  const controller = new AbortController();
  const ctx = {
    skills: { registerProvider(factory) {
      provider = factory({signal: controller.signal, invalidate() {}});
    } },
    logger: {warn(message) { assert.fail(message); }},
    get() { return undefined; },
    effect(fn) { lifecycle.push(fn); }, on() {},
  };
  apply(ctx, {includeDefaultRoots:false, watch:false,
    bundledSkillDir:fileURLToPath(new URL('../../enterprise-plugin/skills',import.meta.url))});
  try {
    const candidates = await provider.list({});
    assert.equal(candidates.length,4);
    for (const candidate of candidates) {
      const skill = await provider.get(candidate,{});
      const raw = await readFile(candidate.locator.path,'utf8');
      const original = raw.replace(/^---\r?\n[\s\S]*?\r?\n---(?:\r?\n|$)/u,'').trim();
      assert.equal(skill.content,original);
      assert.equal(candidate.resourceBase.kind,'opaque');
      assert.equal(skill.resourceBase.kind,'opaque');
      assert.equal(skill.resourceBase.path,undefined);
    }
  } finally {
    controller.abort();
    for (const fn of lifecycle) for (const dispose of fn()) await dispose();
  }
});
