import { describe, expect, it } from 'vitest';
import { selectionSnapshot } from './selection-snapshot';

describe('selection version snapshots', () => {
  it('retains selected versions across polling and pagination, captures newly selected rows', () => {
    const original = { id: 'a', revision: 0 };
    expect(selectionSnapshot([original], ['a', 'b'], [{ id: 'a', revision: 9 }, { id: 'b', revision: 2 }]))
      .toEqual([original, { id: 'b', revision: 2 }]);
    expect(selectionSnapshot([original], ['a'], [])).toEqual([original]);
  });
  it('only recaptures the current revision after deselection and explicit reselection', () => {
    const visible = [{ id: 'a', revision: 2 }];
    expect(selectionSnapshot([{ id: 'a', revision: 1 }], [], visible)).toEqual([]);
    expect(selectionSnapshot([], ['a'], visible)).toEqual(visible);
  });
});
