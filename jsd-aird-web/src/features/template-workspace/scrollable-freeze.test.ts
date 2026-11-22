import { describe, expect, it } from 'vitest';
import { scrollableFreeze, workbookWithScrollableFreeze } from './scrollable-freeze';

describe('scrollable frozen panes', () => {
  it('normalizes all sheets before initialization without changing the source snapshot', () => {
    const freeze = { startRow: 62, startColumn: -1, ySplit: 62, xSplit: 0 };
    const source = { sheets: { first: { freeze, defaultRowHeight: 25 }, second: {
      freeze: { ...freeze, startRow: 1, ySplit: 1 }, rowData: { 0: { h: 30 } },
    } } };
    const next = workbookWithScrollableFreeze(source, { width: 700, height: 300 });
    expect(next.sheets.first?.freeze).toMatchObject({ ySplit: 0, startRow: -1 });
    expect(next.sheets.second?.freeze).toMatchObject({ ySplit: 1 });
    expect(source.sheets.first.freeze.ySplit).toBe(62);
  });
  it('releases imported 62-row freezes that leave no main viewport', () => {
    expect(scrollableFreeze({ startRow: 62, startColumn: 1, ySplit: 62, xSplit: 1 },
      { width: 700, height: 400 }, { width: 90, height: 1800 }))
      .toEqual({ startRow: -1, startColumn: 1, ySplit: 0, xSplit: 1 });
  });
  it('retains useful headers and handles horizontal freezes and short windows', () => {
    const freeze = { startRow: 1, startColumn: 3, ySplit: 1, xSplit: 3 };
    expect(scrollableFreeze(freeze, { width: 700, height: 400 }, { width: 300, height: 30 })).toEqual(freeze);
    expect(scrollableFreeze(freeze, { width: 300, height: 50 }, { width: 300, height: 30 }))
      .toEqual({ startRow: -1, startColumn: -1, ySplit: 0, xSplit: 0 });
    expect(freeze.ySplit).toBe(1);
  });
});
