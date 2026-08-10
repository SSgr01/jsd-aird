import { describe, expect, it } from 'vitest';

import { getAtPath, setAtPath } from './path-utils';

describe('wildcard data paths', () => {
  it('projects a child value from every collection record', () => {
    expect(getAtPath({ materials: [{ code: 'A' }, { code: 'B' }] }, '/materials/*/code'))
      .toEqual(['A', 'B']);
  });

  it('writes child arrays without erasing sibling fields', () => {
    const result = setAtPath(
      { materials: [{ code: 'A' }, { code: 'B' }] },
      '/materials/*/quantity',
      [10, 20],
    );
    expect(result).toEqual({
      materials: [
        { code: 'A', quantity: 10 },
        { code: 'B', quantity: 20 },
      ],
    });
  });

  it('creates collection records for a previously empty parent', () => {
    expect(setAtPath({}, '/materials/*/code', ['A', 'B'])).toEqual({
      materials: [{ code: 'A' }, { code: 'B' }],
    });
  });
});
