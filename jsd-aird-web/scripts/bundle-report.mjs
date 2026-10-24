import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { gzipSync } from 'node:zlib';

// Follow static imports, including shared chunks: checking only the entry file
// misses regressions when a large dependency moves into a preloaded chunk.
export async function bundleReport(dist = resolve('dist')) {
  const manifest = JSON.parse(await readFile(resolve(dist, '.vite/manifest.json'), 'utf8'));
  const files = new Map();
  async function summarize(keys) {
    const visited = new Set();
    const assets = new Set();
    function visit(key) {
      if (visited.has(key)) return;
      const chunk = manifest[key];
      if (!chunk) throw new Error(`Missing build manifest entry: ${key}`);
      visited.add(key);
      assets.add(chunk.file);
      for (const css of chunk.css || []) assets.add(css);
      for (const dependency of chunk.imports || []) visit(dependency);
    }
    keys.forEach(visit);
    let bytes = 0;
    let gzipBytes = 0;
    for (const file of assets) {
      if (!files.has(file)) {
        const body = await readFile(resolve(dist, file));
        files.set(file, { bytes: body.length, gzipBytes: gzipSync(body).length });
      }
      const size = files.get(file);
      bytes += size.bytes;
      gzipBytes += size.gzipBytes;
    }
    return { bytes, gzipBytes, files: [...assets].sort() };
  }
  const entry = Object.keys(manifest).find((key) => manifest[key].isEntry);
  if (!entry) throw new Error('Missing build entry');
  // The bootstrap imports App in parallel with /auth/me. Rollup may turn App
  // into a shared _App-*.js entry (without a src key), so follow the bootstrap's
  // dynamic roots too. Moving startup code into that chunk cannot bypass gates.
  const startup = [entry, ...(manifest[entry].dynamicImports || [])];
  const layout = 'src/layouts/BasicLayout.tsx';
  const shell = manifest[layout] ? [...startup, layout] : startup;
  const report = { startup: await summarize(startup), authenticatedShell: await summarize(shell), pages: {} };
  for (const key of Object.keys(manifest).filter((key) => key.startsWith('src/pages/') && manifest[key].isDynamicEntry)) {
    const standalone = key.startsWith('src/pages/auth/') || key.startsWith('src/pages/template-render/')
      || key === 'src/pages/production-orders/ProductionWorkspacePage.tsx';
    report.pages[key] = await summarize([...(standalone ? startup : shell), key]);
  }
  return report;
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(import.meta.filename)) {
  console.log(JSON.stringify(await bundleReport(), null, 2));
}
