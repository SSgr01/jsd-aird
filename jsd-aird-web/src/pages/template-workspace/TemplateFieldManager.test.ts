import { describe, expect, it } from 'vitest';

import { normalizeAddress, validateAddress } from './coordinates';

describe('template field coordinates', () => {
  it('normalizes customer input and accepts cells or continuous ranges', () => {
    expect(normalizeAddress(' $b$7:$d$10 ')).toBe('B7:D10');
    expect(validateAddress('A2', true)).toBeUndefined();
    expect(validateAddress('B7:D10', false)).toBeUndefined();
    expect(validateAddress('', false)).toBeUndefined();
  });

  it('rejects ranges for label positions and malformed addresses', () => {
    expect(validateAddress('A2:B2', true)).toContain('单个单元格');
    expect(validateAddress('2A', false)).toContain('连续范围');
  });
});
