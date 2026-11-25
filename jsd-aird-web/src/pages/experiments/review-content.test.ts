import { expect, it } from 'vitest';
import { reviewContentKey } from './review-content';

it('ignores renderer layout, formula caches and object key order', () => {
  const model = { documentSnapshot: { sheets: { s: { cellData: { 0: { 0: { f: '=SUM(B1:C1)', v: 10 }, 1: { v: 0 } } } } } } };
  const expected = reviewContentKey(model);
  const changed = structuredClone(model);
  changed.documentSnapshot.sheets.s.cellData[0][0].v = 20;
  Object.assign(changed.documentSnapshot.sheets.s, { zoomRatio: 2, scrollTop: 300 });
  expect(reviewContentKey(changed)).toBe(expected);
  changed.documentSnapshot.sheets.s.cellData[0][1].v = 1;
  expect(reviewContentKey(changed)).not.toBe(expected);
});

it('retains changes to experimental field definitions', () => {
  const model = { documentSnapshot: { sheets: { s: { custom: { templateInstance: { model: { fields: [{ unit: 'g' }] }, revision: 1 } } } } } };
  const expected = reviewContentKey(model);
  model.documentSnapshot.sheets.s.custom.templateInstance.revision++;
  expect(reviewContentKey(model)).toBe(expected);
  model.documentSnapshot.sheets.s.custom.templateInstance.model.fields[0]!.unit = 'kg';
  expect(reviewContentKey(model)).not.toBe(expected);
});
