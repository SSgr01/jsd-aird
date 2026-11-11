import { lstat, realpath } from 'node:fs/promises';
import { isAbsolute, relative, resolve, sep } from 'node:path';
import {hostDirectory} from './path-view.mjs';

function denied() { return Object.assign(new Error('EXECUTION_DIRECTORY_DENIED'), { status: 403 }); }

/** Directory admission only. Registering a native Workspace grants no business access. */
export async function executionDirectory(root, value) {
  value = hostDirectory(root, value);
  if (typeof value !== 'string' || !isAbsolute(value) || /[\x00-\x1f]/.test(value)) throw denied();
  if (process.platform === 'win32' && !/^(?:[A-Za-z]:[\\/]|[\\/]{2}[^\\/]+[\\/]+[^\\/]+)/.test(value)) throw denied();
  const base = resolve(root), target = resolve(value), within = relative(base, target);
  if (within === '..' || within.startsWith('..' + sep) || isAbsolute(within)) throw denied();
  const parts = within === '' ? [] : within.split(sep);
  if (parts.some(part => /[:\x00-\x1f]/.test(part) || /[. ]$/.test(part))) throw denied();
  let probe = base;
  for (const part of ['', ...parts]) {
    probe = part ? resolve(probe, part) : probe;
    const info = await lstat(probe);
    if (info.isSymbolicLink() || !info.isDirectory()) throw denied();
  }
  const canonicalBase = await realpath(base), canonicalTarget = await realpath(target);
  const canonicalWithin = relative(canonicalBase, canonicalTarget);
  if (canonicalWithin === '..' || canonicalWithin.startsWith('..' + sep) || isAbsolute(canonicalWithin)) throw denied();
  return canonicalTarget;
}
