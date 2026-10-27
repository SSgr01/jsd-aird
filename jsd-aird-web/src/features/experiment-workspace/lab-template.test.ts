import { describe, expect, it } from 'vitest';
import { copyLabCells, labAddress, labMassFormula } from './lab-template';

describe('unified laboratory workbook', () => {
  it('copies relative formula coordinates without changing absolute coordinates or quoted descriptions', () => {
    const source = {
      f: '=IF(A1="A1",SUM($B$52:$B$59,H52:H59),$C1+D$2)',
      custom: { lab: { fieldCode: 'formula.total' } },
    };
    const copied = copyLabCells([[source, { v: '旧样品', s: 'style-1' }]], 1, 2);
    expect(copied[0]![0]!.f).toBe('=IF(C2="A1",SUM($B$52:$B$59,J53:J60),$C2+F$2)');
    expect(copied[0]![1]).toEqual({ v: '', s: 'style-1' });
    expect(source.f).toContain('H52:H59');
  });
  it('keeps masses in grams and suppresses an incomplete total', () => {
    expect(labMassFormula(8, 51, 60)).toContain('$B$52:$B$61');
    expect(labMassFormula(8, 51, 60)).toContain('LOWER(TRIM(I52:I61))');
    expect(labAddress(0, 26)).toBe('AA1');
  });
});
