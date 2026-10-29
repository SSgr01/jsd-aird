import { describe, expect, it } from 'vitest';
import { documentContentSignature, isWorkbookContentCommand } from './office-content';
import { isCellMutationCommand } from './live-field-discovery';

describe('Office persistence changes', () => {
  it.each([
    'sheet.mutation.set-range-style', 'sheet.mutation.set-drawing-apply',
    'sheet.mutation.add-data-validation', 'sheet.mutation.add-conditional-formatting',
    'sheet.mutation.set-row-height', 'sheet.mutation.set-col-width',
    'sheet.mutation.set-note', 'sheet.mutation.add-hyper-link',
  ])('saves %s without creating fields', id => {
    expect(isWorkbookContentCommand({ id, type: 2 })).toBe(true);
    expect(isCellMutationCommand(id)).toBe(false);
  });

  it.each([
    'sheet.operation.set-selections', 'sheet.operation.set-activate-cell-edit',
    'formula.mutation.set-formula-calculation-result', 'doc.mutation.rich-text-editing',
    'sheet.mutation.set-permission',
  ])('ignores transient %s', id => {
    expect(isWorkbookContentCommand({ id })).toBe(false);
  });

  it('detects picture moves, deletion and page layout changes without relying on text edits', () => {
    const snapshot = { body: { dataStream: '\b\r\n', customBlocks: [{ blockId: 'picture', startIndex: 0 }] },
      drawings: { picture: { transform: { width: 100, height: 60 } } }, documentStyle: { marginTop: 72 } };
    const signature = documentContentSignature(snapshot);
    expect(documentContentSignature({ ...snapshot, zoomRatio: 2 })).toBe(signature);
    expect(documentContentSignature({ ...snapshot, drawings: {} })).not.toBe(signature);
    expect(documentContentSignature({ ...snapshot, drawings: { picture: { transform: { width: 200, height: 60 } } } })).not.toBe(signature);
    expect(documentContentSignature({ ...snapshot, documentStyle: { marginTop: 90 } })).not.toBe(signature);
  });

  it('distinguishes Univer 1.2 style patches from value patches on the same mutation', () => {
    expect(isCellMutationCommand('sheet.mutation.set-range-values', {
      cellValue: { 0: { 0: { s: { bl: 1 } } } }, trigger: 'sheet.command.set-style',
    })).toBe(false);
    expect(isCellMutationCommand('sheet.mutation.set-range-values', {
      cellValue: { 0: { 0: { v: '新增字段' } } },
    })).toBe(true);
    expect(isCellMutationCommand('sheet.mutation.set.numfmt')).toBe(false);
    expect(isWorkbookContentCommand({ id: 'data-validation.mutation.addRule', type: 2 })).toBe(true);
    expect(isCellMutationCommand('sheet.command.addDataValidation')).toBe(false);
    expect(isCellMutationCommand('ui.operation.open-replace-dialog')).toBe(false);
    expect(isCellMutationCommand('sheet.mutation.set-range-values', {
      cellValue: { 4: { 3: { p: { body: { dataStream: '\b\r\n' }, drawings: { image: {} } } } } },
    })).toBe(false);
  });
});
