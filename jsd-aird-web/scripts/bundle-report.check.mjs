import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, join, sep } from 'node:path';
import { bundleReport } from './bundle-report.mjs';

test('counts the shared App bootstrap, CSS and circular dependencies once', async () => {
  const parent = resolve(tmpdir());
  const dist = await mkdtemp(join(parent, 'aird-bundle-report-'));
  try {
    await mkdir(join(dist, '.vite'));
    const manifest = {
      'index.html': { isEntry: true, file: 'entry.js', dynamicImports: ['_App-shared.js'] },
      '_App-shared.js': { file: 'App.js', imports: ['index.html', '_common.js'], css: ['App.css'] },
      '_common.js': { file: 'common.js' },
      'src/layouts/BasicLayout.tsx': { file: 'layout.js', imports: ['_App-shared.js'] },
      'src/pages/project/ProjectListPage.tsx': { file: 'page.js', isDynamicEntry: true, imports: ['_common.js'] },
    };
    await writeFile(join(dist, '.vite/manifest.json'), JSON.stringify(manifest));
    for (const name of ['entry.js','App.js','common.js','App.css','layout.js','page.js']) await writeFile(join(dist,name), 'x'.repeat(1000));
    const report = await bundleReport(dist);
    assert.equal(report.startup.bytes, 4000);
    assert.deepEqual(report.startup.files, ['App.css','App.js','common.js','entry.js']);
    assert.equal(report.pages['src/pages/project/ProjectListPage.tsx'].bytes, 6000);
  } finally {
    // Only the freshly-created, resolved task directory can be recursively removed.
    assert.ok(resolve(dist).startsWith(parent + sep));
    assert.ok(dist.split(sep).at(-1).startsWith('aird-bundle-report-'));
    await rm(dist, {recursive:true,force:true});
  }
});
