import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readFileSync } from 'node:fs';
const upstream = fileURLToPath(new URL('../../vendor/deepseek-harness/', import.meta.url));
const patch = fileURLToPath(new URL('./patches/chemistry-markdown.patch', import.meta.url));
const run = args => execFileSync('git', args, {cwd:upstream,stdio:'pipe'});
// Windows checkouts may convert patch context line endings.
try { run(['apply','--reverse','--check','--ignore-space-change',patch]); }
catch {
  // Fail on upstream drift instead of silently shipping an unpatched chat.
  run(['apply','--check',patch]);
  run(['apply',patch]);
}
// The patch carries a generated copy so Harness builds stay local to its package graph.
const parser = readFileSync(new URL('../../jsd-aird-web/src/components/markdown/chemistry-input.ts', import.meta.url), 'utf8');
const nativeParser = readFileSync(new URL('../../vendor/deepseek-harness/packages/client/ui-primitives/src/markdown/chemistry-input.ts', import.meta.url), 'utf8');
if (parser.replaceAll('\r\n', '\n') !== nativeParser.replaceAll('\r\n', '\n'))
  throw new Error('Chemistry parser drift: regenerate the Harness chemistry-markdown.patch from the shared chemistry-input.ts');
