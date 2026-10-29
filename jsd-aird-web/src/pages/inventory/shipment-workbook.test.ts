import { describe, expect, it } from 'vitest';
import * as XLSX from 'xlsx';
import { isShipmentWorkbook, parseShipmentWorkbook, shipmentWorkbookTemplate } from './shipment-workbook';

const rows = [
  ['出货记录表'],
  ['日期', '2025-03-07'],
  ['客户名', '品名', '批次号', '规格', '数量', '总数量', '快递公司', '备注'],
  ['华福', 'DSP', 'B-1', 2, 3, '', '顺丰', '原始备注'],
  ['华福', 'DSP', 'B-2', 1.5, 2, 3, '', ''],
];

describe('出货记录表附件格式', () => {
  it('下载模板不包含出货单号栏', () => {
    const workbook = shipmentWorkbookTemplate();
    const sheet = workbook.Sheets[workbook.SheetNames[0]!];
    const templateRows = XLSX.utils.sheet_to_json<string[]>(sheet!, { header: 1, defval: '' });
    expect(templateRows[1]?.[0]).toBe('日期');
    expect(templateRows[1]?.slice(1).every((cell) => cell === '')).toBe(true);
    expect(templateRows[2]).toEqual(rows[2]);
  });

  it('按同一单号生成多条明细并保留原始备注', () => {
    const sheet = XLSX.utils.aoa_to_sheet(rows);
    expect(isShipmentWorkbook(sheet)).toBe(true);
    const result = parseShipmentWorkbook(sheet);
    expect(result.details).toHaveLength(2);
    expect(result.details[0]).toMatchObject({
      shipmentNo: result.shipmentNo, businessDate: '2025-03-07',
      productName: 'DSP', customerName: '华福', packageQuantity: 3, specification: '2', note: '原始备注',
    });
    expect(result.details[1]).toMatchObject({ shipmentNo: result.shipmentNo, batchNo: 'B-2' });
  });

  it('按用户确认的日期格式读取 B2', () => {
    const sheet = XLSX.utils.aoa_to_sheet(rows.map((row, index) => index === 1 ? ['日期', '2025.03-07'] : row));
    expect(parseShipmentWorkbook(sheet).details[0]?.businessDate).toBe('2025-03-07');
  });
});
