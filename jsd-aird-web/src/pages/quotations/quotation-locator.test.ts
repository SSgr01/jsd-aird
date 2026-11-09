import { describe, expect, it } from 'vitest';
import type { QuotationDetail } from '@/services/quotations/quotation-api';
import { changedQuotationCells, fieldKey, quotationLocations } from './quotation-locator';

describe('quotationLocations', () => {
  it('locates customer values and item fields in the imported sheet', () => {
    const snapshot = { sheetOrder: ['sheet-1'], sheets: { 'sheet-1': {
      name: '报价单', cellData: {
        '0': { '0': { v: '客户名称' }, '1': { v: '甲公司' } },
        '2': { '0': { v: '产品' }, '1': { v: '单价' }, '2': { v: '规格' } },
        '3': { '0': { v: '涂料' }, '1': { v: '12.5' }, '2': { v: '20kg' } },
      },
    } } };
    const detail = { items: [{ sourceRowNo: 4 }] } as QuotationDetail;
    const result = quotationLocations(snapshot, detail);
    expect(result[fieldKey('customer', '客户名称')]).toMatchObject({ address: 'B1', rawValue: '甲公司' });
    expect(result[fieldKey('items', '单价', 0)]).toMatchObject({ address: 'B4', rawValue: '12.5' });
    expect(result[fieldKey('items', '规格', 0)]).toMatchObject({ address: 'C4', rawValue: '20kg' });
  });

  it('does not invent a location for newly added rows', () => {
    const snapshot = { sheetOrder: ['sheet-1'], sheets: { 'sheet-1': {
      name: '报价单', cellData: {
        '0': { '0': { v: '产品' }, '1': { v: '单价' } },
        '1': { '0': { v: '原产品' }, '1': { v: '12' } },
      },
    } } };
    const detail = { items: [{ sourceRowNo: 1 }] } as QuotationDetail;
    expect(quotationLocations(snapshot, detail)[fieldKey('items', '产品', 0)]).toBeUndefined();
  });

  it('locates an empty remark cell so a new value updates the item field', () => {
    const snapshot = { sheetOrder: ['sheet-1'], sheets: { 'sheet-1': {
      name: '报价单', cellData: {
        '0': { '0': { v: '产品' }, '1': { v: '单价' }, '2': { v: '备注' } },
        '1': { '0': { v: '涂料' }, '1': { v: '12.5' } },
      },
    } } };
    const detail = { items: [{ sourceRowNo: 2 }] } as QuotationDetail;
    expect(quotationLocations(snapshot, detail)[fieldKey('items', '备注', 0)])
      .toMatchObject({ address: 'C2', rowIndex: 1, columnIndex: 2, rawValue: '' });
    const changed = { sheetOrder: ['sheet-1'], sheets: { 'sheet-1': {
      name: '报价单', cellData: {
        '0': { '0': { v: '产品' }, '1': { v: '单价' }, '2': { v: '备注' } },
        '1': { '0': { v: '涂料' }, '1': { v: '12.5' }, '2': { v: '5' } },
      },
    } } };
    expect(changedQuotationCells(changed, snapshot, quotationLocations(snapshot, detail)))
      .toEqual({ [fieldKey('items', '备注', 0)]: '5' });
  });
});
