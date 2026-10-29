import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import type { Plugin } from 'vite';

export function buildInfoPlugin(): Plugin {
  const revision = process.env.BUILD_REVISION || execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
  if (!/^[a-f0-9]{40}$/.test(revision)) throw new Error('BUILD_REVISION must be a full Git revision');
  if (process.env.RELEASE_BUILD === '1') {
    const actual = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
    const changes = execFileSync('git', ['status', '--porcelain', '--untracked-files=normal'], { encoding: 'utf8' }).trim();
    if (actual !== revision || changes) throw new Error('Release builds require a clean checkout at BUILD_REVISION');
  }
  const builtAt = new Date().toISOString();
  return {
    name: 'aird-build-info',
    config: () => ({ define: { 'import.meta.env.VITE_BUILD_REVISION': JSON.stringify(revision) } }),
    transformIndexHtml: (html) => html.replace(/<style data-startup-style>[\s\S]*?<\/style>/, () => `<style data-startup-style>${readFileSync(new URL('../src/styles/startup.css', import.meta.url), 'utf8')}</style>`),
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'build-info.json', source: JSON.stringify({ component: 'frontend', revision, builtAt }) });
    },
  };
}
