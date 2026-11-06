import { relative, resolve, sep } from 'node:path';
import BrowseDirectoryPicker from '../../../vendor/deepseek-harness/packages/host/directory-picker-browse/lib/index.js';
import { executionDirectory } from '../../deepseek/src/paths.mjs';

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
        const value = await native.list(current, signal);
        const entries = [];
        for (const entry of value.entries) {
          signal?.throwIfAborted();
          try { await executionDirectory(root, entry.path); entries.push(entry); } catch { /* no linked or foreign directories */ }
        }
        return { ...value, home: root, entries, crumbs: value.crumbs.filter(entry => {
          const inside = relative(root, entry.path);
          return inside === '' || (!inside.startsWith('..' + sep) && inside !== '..' && !/^[A-Za-z]:/.test(inside));
        }) };
      },
      createDirectory: async (path, name) => {
        const parent = await executionDirectory(root, path);
        if (typeof name !== 'string' || !name.trim() || /[\\/:\x00-\x1f]/.test(name) || /[. ]$/.test(name) || name === '.') {
          throw new Error('INVALID_DIRECTORY_NAME');
        }
        const created = await native.createDirectory(parent, name);
        return executionDirectory(root, created);
      },
    };
  }
  capability() { return this.managed; }
}
