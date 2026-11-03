// @vitest-environment node
import { it, expect } from 'vitest';
import postcss from 'postcss';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { browserCssCompat } from './browser-css-compat';

const convert = (css: string) => postcss([browserCssCompat()]).process(css, { from: undefined }).css;

it('generates vh fallback and gates variable color-mix behind @supports', () => {
  const css = convert('.panel { height: calc(100dvh - 64px); border: 1px solid color-mix(in srgb, var(--app-primary) 14%, var(--app-border)); }');
  expect(css).toContain('height: calc(100vh - 64px)');
  expect(css).toContain('height: calc(100dvh - 64px)');
  expect(css).toMatch(/border: 1px solid #[a-f0-9]{6}/);
  expect(css).toContain('@supports (color: color-mix(in srgb, red, blue))');
  expect(css).toContain('border: 1px solid color-mix(in srgb, var(--app-primary) 14%, var(--app-border))');
});

it('preserves media conditions, selector specificity and transparent focus rings', () => {
  const css = convert('@media (max-width: 800px) { .panel:hover { outline: 3px solid color-mix(in srgb, var(--app-focus) 60%, transparent); } }');
  const root = postcss.parse(css);
  const media = root.first;
  expect(media?.type).toBe('atrule');
  if (media?.type !== 'atrule') throw new Error('Missing media query');
  expect(media.nodes?.[1]?.type).toBe('atrule');
  expect(css).toContain('rgb(36 72 216 / 60%)');
  expect(css.match(/\.panel:hover/g)).toHaveLength(2);
});

it('covers all application styles and makes unknown color tokens fail visibly', () => {
  function visit(directory: string): void {
    for (const item of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, item.name);
      if (item.isDirectory()) visit(path);
      else if (item.name.endsWith('.css')) expect(() => convert(readFileSync(path, 'utf8'))).not.toThrow();
    }
  }
  visit(fileURLToPath(new URL('../src', import.meta.url)));
  expect(() => convert('.x { color: color-mix(in srgb, var(--app-unknown) 10%, transparent); }')).toThrow('Missing Chromium 106 color fallback');
});

it('loads compatibility before application and standalone editor imports', () => {
  for (const path of ['src/main.tsx', 'src/features/template-workspace/UniverSheetsEditor.tsx', 'src/features/template-workspace/UniverDocsEditor.tsx']) {
    expect(readFileSync(new URL('../' + path, import.meta.url), 'utf8').split('\n')[0]).toContain("import '@/compat/browser'");
  }
  expect(readFileSync(new URL('./univer-smoke-fixture.mjs', import.meta.url), 'utf8').split('\n')[0]).toContain('/src/compat/browser.ts');
});
