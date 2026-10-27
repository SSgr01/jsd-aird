import dayjs from 'dayjs';
import * as XLSX from 'xlsx';

const columns = ['客户名', '品名', '批次号', '规格', '数量', '总数量', '快递公司', '备注'];
const cellText = (value: unknown): string =>
  typeof value === 'string' || typeof value === 'number' ? String(value).trim() : '';

export function isShipmentWorkbook(sheet: XLSX.WorkSheet): boolean {
  const rows = XLSX.utils.sheet_to_json<unknown[]>(sheet, { header: 1, defval: '' });
  return cellText(rows[0]?.[0]) === '出货记录表';
}

export function shipmentWorkbookTemplate(): XLSX.WorkBook {
  const workbook = XLSX.utils.book_new();
  const sheet = XLSX.utils.aoa_to_sheet([
    ['出货记录表'],
    ['日期', ''],
    columns,
  ]);
  sheet['!cols'] = [{ wch: 25 }, { wch: 26 }, { wch: 24 }, { wch: 14 },
    { wch: 12 }, { wch: 14 }, { wch: 20 }, { wch: 32 }];
  XLSX.utils.book_append_sheet(workbook, sheet, '出货记录');
  return workbook;
}

export function parseShipmentWorkbook(
  sheet: XLSX.WorkSheet,
  defaultShipmentNo?: string,
): { shipmentNo: string; details: Record<string, unknown>[] } {
  const rows = XLSX.utils.sheet_to_json<unknown[]>(sheet, { header: 1, defval: '', raw: true });
  if (!columns.every((column, index) => cellText(rows[2]?.[index]) === column))
    throw new Error('第 3 行表头应为：' + columns.join('、'));
  const rawDate = rows[1]?.[1];
  const parseDateCode = (XLSX as unknown as {
    SSF: { parse_date_code: (value: number) => { y: number; m: number; d: number } | null };
  }).SSF.parse_date_code;
  const serial = typeof rawDate === 'number' ? parseDateCode(rawDate) : undefined;
  const businessDate = serial
    ? `${serial.y}-${String(serial.m).padStart(2, '0')}-${String(serial.d).padStart(2, '0')}`
    : cellText(rawDate).replace(/^(\d{4})\.(\d{2})-(\d{2})$/, '$1-$2-$3');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(businessDate) || dayjs(businessDate).format('YYYY-MM-DD') !== businessDate)
    throw new Error('B2 日期须为明确的单日，格式 YYYY-MM-DD；请先修正原表日期');
  const shipmentNo = cellText(rows[1]?.[3]) || defaultShipmentNo ||
    `CH-${businessDate.replaceAll('-', '')}-${crypto.randomUUID().slice(0, 8).toUpperCase()}`;
  const details = rows.slice(3).flatMap((row, index) => {
    if (row.every((cell) => cellText(cell) === '')) return [];
    const rowNo = index + 4;
    const value = (column: number) => cellText(row[column]);
    const customerName = value(0);
    const productName = value(1);
    if (!customerName || !productName) throw new Error(`第 ${rowNo} 行：客户名和品名不能为空`);
    const specification = Number(value(3));
    const packageQuantity = Number(value(4));
    if (!Number.isFinite(specification) || specification <= 0 || !Number.isFinite(packageQuantity) || packageQuantity <= 0)
      throw new Error(`第 ${rowNo} 行：规格和数量必须为大于 0 的数字`);
    const total = Math.round(specification * packageQuantity * 1_000_000) / 1_000_000;
    if (value(5) && (!Number.isFinite(Number(value(5))) || Math.abs(Number(value(5)) - total) > 0.000001))
      throw new Error(`第 ${rowNo} 行：总数量与规格 × 数量不一致`);
    return [{
      shipmentNo, businessDate, productName, customerName,
      batchNo: value(2), specification: String(specification),
      packageQuantity, courierCompany: value(6), note: value(7),
    }];
  });
  if (!details.length) throw new Error('导入文件没有出货明细');
  if (details.length > 100) throw new Error('一次最多导入 100 条出货明细');
  return { shipmentNo, details };
}
