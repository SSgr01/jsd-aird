import { describe, expect, it } from 'vitest';
import { compatibleLabTotalFormula, compatibleLabTotals, copyLabCells, labAddress, labMassFormula } from './lab-template';

describe('unified laboratory workbook', () => {
  it('copies relative formula coordinates without changing absolute coordinates or quoted descriptions', () => {
    const source = {
      f: '=IF(A1="A1",SUM($B$52:$B$59,H52:H59),$C1+D$2)',
      custom: { lab: { fieldCode: 'formula.total' } },
    };
    const copied = copyLabCells([[source, { v: '旧样品', s: 'style-1' }]], 1, 2);
    expect(copied[0]![0]!.f).toBe('=IF(C2="A1",SUM($B$52:$B$59,J53:J60),$C2+F$2)');
    expect(copied[0]![1]).toEqual({ v: null, t: null, s: 'style-1' });
    expect(source.f).toContain('H52:H59');
  });
  it('keeps masses in grams and suppresses an incomplete total', () => {
    expect(labMassFormula(8, 51, 60)).toContain('AND($B$52<>"",I52="")');
    expect(labMassFormula(8, 51, 60)).toContain('LOWER(TRIM(I61))');
    expect(labMassFormula(8, 51, 60)).not.toContain('SUMPRODUCT');
    expect(labAddress(0, 26)).toBe('AA1');
  });
  it('upgrades legacy totals while preserving user formulas, manual values and the source snapshot', () => {
    const old = '=IF(COUNTA(C52:C59)=0,"",SUMPRODUCT(IFERROR(--SUBSTITUTE(LOWER(TRIM(C52:C59)),"g",""),-1)))';
    const snapshot = { sheets: { s: { custom: { labTemplate: {} }, cellData: { 59: {
      2: { f: old, custom: { lab: { fieldCode: 'formula.total' } } },
      3: { f: '=SUM(D52:D59)', custom: { lab: { fieldCode: 'formula.total' } } },
      4: { v: 31, custom: { lab: { fieldCode: 'formula.total' } } },
    } } } } };
    const next = compatibleLabTotals(snapshot) as typeof snapshot;
    expect(next.sheets.s.cellData[59][2].f).toBe(labMassFormula(2, 51, 58));
    expect(next.sheets.s.cellData[59][3]).toEqual(snapshot.sheets.s.cellData[59][3]);
    expect(next.sheets.s.cellData[59][4]).toEqual(snapshot.sheets.s.cellData[59][4]);
    expect(snapshot.sheets.s.cellData[59][2].f).toBe(old);
  });
  it('rebuilds scalar terms after the material range expands or shrinks', () => {
    const inserted = labMassFormula(2, 51, 58).replace('COUNTA(C52:C59)', 'COUNTA(C52:C60)');
    expect(compatibleLabTotalFormula(inserted, 2)).toBe(labMassFormula(2, 51, 59));
    const deleted = labMassFormula(2, 51, 59).replace('COUNTA(C52:C60)', 'COUNTA(C52:C59)');
    expect(compatibleLabTotalFormula(deleted, 2)).toBe(labMassFormula(2, 51, 58));
    expect(compatibleLabTotalFormula('=SUM(C52:C59)', 2)).toBe('=SUM(C52:C59)');
  });
});
