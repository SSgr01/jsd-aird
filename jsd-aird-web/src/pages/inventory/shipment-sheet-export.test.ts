import ExcelJS from 'exceljs';
import { describe, expect, it } from 'vitest';
import type { Shipment } from '@/services/inventory/inventory-api';
import { createShipmentSheetWorkbook } from './shipment-sheet-export';

const details = [
  {
    customerName: '康得新', productName: 'A5000', batchNo: 'CH-20261026-091510',
    specification: '1', packageQuantity: 1, totalQuantityKg: 1,
    courierCompany: '申通', note: '原始备注',
  },
  {
    customerName: '东莞扬永兴', productName: 'A5000K', batchNo: 'CH-20261026-103548',
    specification: '1', packageQuantity: 1, totalQuantityKg: 1,
    courierCompany: '申通', note: '第二条',
  },
] as Shipment[];

describe('出货明细页面导出', () => {
  it('写出页面的标题、日期、八列明细及至少十二行', async () => {
    const source = createShipmentSheetWorkbook('2026-10-26', details);
    const bytes = await source.xlsx.writeBuffer();
    const workbook = await new ExcelJS.Workbook().xlsx.load(bytes);
    const sheet = workbook.getWorksheet('出货记录');
    expect(sheet).toBeDefined();
    expect(sheet!.getCell('A1').value).toBe('出货记录表');
    expect(sheet!.getCell('H1').isMerged).toBe(true);
    expect(sheet!.getCell('A2').value).toBe('日期');
    expect(sheet!.getCell('B2').value).toBe('2026-10-26');
    expect(sheet!.getRow(3).values).toEqual([
      undefined, '客户名', '品名', '批次号', '规格', '数量', '总数量', '快递公司', '备注',
    ]);
    expect(sheet!.getCell('H4').value).toBe('原始备注');
    expect(sheet!.getCell('C5').value).toBe('CH-20261026-103548');
    expect(sheet!.getCell('A15').border?.bottom?.style).toBe('thin');
    expect(sheet!.getColumn(8).width).toBe(30);
  });
});
