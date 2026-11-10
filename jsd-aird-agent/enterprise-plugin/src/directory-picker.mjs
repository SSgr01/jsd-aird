import { relative, resolve, sep } from 'node:path';
import BrowseDirectoryPicker from '../../../vendor/deepseek-harness/packages/host/directory-picker-browse/lib/index.js';
import { DirectoryPickerError } from '../../../vendor/deepseek-harness/packages/host/directory-picker/lib/index.js';
import { executionDirectory } from '../../deepseek/src/paths.mjs';
import { browserMetadata, browserPath } from '../../deepseek/src/path-view.mjs';

/** Keep the native directory browser and its bounds, confined to the user's execution storage. */
export default class EnterpriseDirectoryPicker extends BrowseDirectoryPicker {
  constructor(ctx) {
    super(ctx, { maxEntries: 1000 });
    const native = super.capability();
    const root = resolve(process.env.JSD_DSH_EXECUTION_ROOT);
    this.managed = {
      kind: 'browse',
      list: async (path, signal) => {
        signal?.throwIfAborted();
        const current = await executionDirectory(root, path ?? root);
        const value = await native.list(current, signal).catch(error => { throw visibleFailure(root, error); });
        const entries = [];
        for (const entry of value.entries) {
          signal?.throwIfAborted();
          try { await executionDirectory(root, entry.path); entries.push(entry); } catch { /* no linked or foreign directories */ }
        }
        return browserMetadata({ ...value, home: root, entries, crumbs: value.crumbs.filter(entry => {
          const inside = relative(root, entry.path);
          return inside === '' || (!inside.startsWith('..' + sep) && inside !== '..' && !/^[A-Za-z]:/.test(inside));
        }) }, root);
      },
      createDirectory: async (path, name) => {
        const parent = await executionDirectory(root, path);
        if (typeof name !== 'string' || !name.trim() || /[\\/:\x00-\x1f]/.test(name) || /[. ]$/.test(name) || name === '.') {
          throw new Error('INVALID_DIRECTORY_NAME');
        }
        const created = await native.createDirectory(parent, name).catch(error => { throw visibleFailure(root, error); });
        return browserPath(root, await executionDirectory(root, created));
      },
    };
  }
  capability() { return this.managed; }
}

// Native filesystem errors include server directories in their message as well
// as their structured path. Keep the native error codes without exposing either.
function visibleFailure(root, error) {
  if (!(error instanceof DirectoryPickerError)) return error;
  const message = error.code === 'directory-exists' ? '该目录已存在。'
    : error.code === 'directory-create-failed' ? '无法创建目录。' : '无法读取目录。';
  return new DirectoryPickerError(error.code, browserPath(root, error.path), message);
}
