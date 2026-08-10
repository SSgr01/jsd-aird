import { describe, expect, it } from 'vitest';

import {
  isCellMutationCommand,
  isSingleCellAddress,
  potentialFieldLabel,
} from './live-field-discovery';

describe('live field discovery guards', () => {
  it('accepts a single ordinary cell, including a same-cell range', () => {
    expect(isSingleCellAddress('B4')).toBe(true);
    expect(isSingleCellAddress('$B$4:$B$4')).toBe(true);
    expect(isSingleCellAddress('B4:C4')).toBe(false);
  });

  it('normalizes a typed label without treating data as a field', () => {
    expect(potentialFieldLabel('客户批号')).toBe('客户批号');
    expect(potentialFieldLabel('客户批号：')).toBe('客户批号');
    expect(potentialFieldLabel('123')).toBeUndefined();
    expect(potentialFieldLabel('=A1')).toBeUndefined();
    expect(potentialFieldLabel('https://example.com')).toBeUndefined();
    expect(potentialFieldLabel('客户批号：123')).toBeUndefined();
    expect(potentialFieldLabel(['客户批号'])).toBeUndefined();
  });

  it('recognizes Univer workbook value mutation commands', () => {
    expect(isCellMutationCommand('sheet.command.set-range-values')).toBe(true);
    expect(isCellMutationCommand('sheet.mutation.set-range-values')).toBe(true);
    expect(isCellMutationCommand('sheet.command.set-cell-value')).toBe(true);
    expect(isCellMutationCommand('sheet.command.set-range-format')).toBe(false);
  });
});
