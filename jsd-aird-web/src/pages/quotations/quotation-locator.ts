import type { QuotationDetail } from '@/services/quotations/quotation-api';

export interface FieldLocation {
  sheetId: string;
  sheetName: string;
  address: string;
  rowIndex: number;
  columnIndex: number;
  rawValue: string;
}
export type QuotationLocations = Record<string, FieldLocation>;

const customerLabels: Record<string, string[]> = {
  '客户名称': ['公司名称', '客户名称', '客户', '公司', '客户名', '单位名称'],
  '关联公司': ['关联公司', '关联企业', '关联单位'],
  '主联系人': ['联系人', '联系人姓名', '对接人'],
  '职位': ['职称', '职务', '职位'],
  '电话': ['电话', '联系电话', '手机', '手机号', '电话号码'],
  '传真': ['传真', '传真号'],
  '邮箱': ['邮箱', '电子邮件', '邮箱地址', 'email', 'e-mail'],
  '地址1': ['地址1', '地址一', '地址', '地址（一）'],
  '地址2': ['地址2', '地址二', '地址（二）'],
  '网站': ['网站', '网址', '公司网站'],
  '备注': ['备注', '说明'],
};
const itemLabels: Record<string, string[]> = {
  '产品': ['产品', '品名', '产品名称', '物料名称'],
  '单价': ['单价', '价格', '报价'],
  '规格': ['规格', '规格型号', '包装规格'],
  '报价日期': ['报价日期', '日期', '报价时间'],
  '客户编码': ['客户编码', '客户代码', '编码', '物料编码'],
  '备注': ['备注', '说明'],
};
const normalize = (value: string) => value.replace(/[\s\u00a0:：()（）]/g, '').toLowerCase();
const cellText = (value: unknown): string => {
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return String(value);
  return value == null ? '' : JSON.stringify(value);
};
export const fieldKey = (section: 'customer' | 'items', name: string, index = 0) =>
  `${section}:${index}:${name}`;

export function changedQuotationCells(
  current: Record<string, unknown>,
  source: Record<string, unknown>,
  locations: QuotationLocations,
): Record<string, string> {
  const read = (snapshot: Record<string, unknown>, location: FieldLocation) => {
    const sheets = snapshot.sheets as Record<string, {
      cellData?: Record<string, Record<string, { v?: unknown }>>;
    }> | undefined;
    return cellText(sheets?.[location.sheetId]?.cellData?.[String(location.rowIndex)]
      ?.[String(location.columnIndex)]?.v);
  };
  return Object.fromEntries(Object.entries(locations)
    .flatMap(([key, location]) => {
      const value = read(current, location);
      return value === read(source, location) ? [] : [[key, value]];
    }));
}

export function quotationLocations(snapshot: Record<string, unknown>, detail: QuotationDetail): QuotationLocations {
  const sheets = snapshot.sheets as Record<string, { name: string; cellData: Record<string, Record<string, { v?: unknown }>> }> | undefined;
  const order = snapshot.sheetOrder as string[] | undefined;
  if (!sheets || !order) return {};
  const locations: QuotationLocations = {};
  const XLSX = { column: (index: number) => {
    let value = index + 1;
    let result = '';
    while (value > 0) { value -= 1; result = String.fromCharCode(65 + value % 26) + result; value = Math.floor(value / 26); }
    return result;
  } };
  for (const sheetId of order) {
    const sheet = sheets[sheetId];
    if (!sheet) continue;
    const rows = sheet.cellData;
    const rowNumbers = Object.keys(rows).map(Number).sort((a, b) => a - b);
    let headerRow = -1;
    let columns: Record<string, number> = {};
    for (const rowNumber of rowNumbers.filter((row) => row < 60)) {
      const row = rows[String(rowNumber)] || {};
      const found: Record<string, number> = {};
      for (const [column, cell] of Object.entries(row)) {
        const text = normalize(cellText(cell.v));
        for (const [field, labels] of Object.entries(itemLabels)) {
          if (labels.some((label) => normalize(label) === text)) found[field] ??= Number(column);
        }
      }
      if (found['产品'] !== undefined && (found['单价'] !== undefined || found['报价日期'] !== undefined)) {
        headerRow = rowNumber;
        columns = found;
        break;
      }
    }
    if (headerRow < 0) continue;
    for (const rowNumber of rowNumbers.filter((row) => row < headerRow)) {
      const row = rows[String(rowNumber)] || {};
      for (const [column, cell] of Object.entries(row)) {
        const text = normalize(cellText(cell.v));
        for (const [field, labels] of Object.entries(customerLabels)) {
          if (!labels.some((label) => normalize(label) === text) || locations[fieldKey('customer', field)]) continue;
          const nextColumn = Number(column) + 1;
          const value = row[String(nextColumn)]?.v;
          locations[fieldKey('customer', field)] = {
            sheetId, sheetName: sheet.name, address: `${XLSX.column(nextColumn)}${rowNumber + 1}`,
            rowIndex: rowNumber, columnIndex: nextColumn,
            rawValue: cellText(value),
          };
        }
      }
    }
    detail.items.forEach((item, index) => {
      if (!item.sourceRowNo || item.sourceRowNo <= headerRow + 1) return;
      const row = rows[String(item.sourceRowNo - 1)] || {};
      if (row[String(columns['产品'])]?.v == null) return;
      for (const [field, column] of Object.entries(columns)) {
        const value = row[String(column)]?.v;
        locations[fieldKey('items', field, index)] = {
          sheetId, sheetName: sheet.name, address: `${XLSX.column(column)}${item.sourceRowNo}`,
          rowIndex: item.sourceRowNo - 1, columnIndex: column,
          rawValue: cellText(value),
        };
      }
    });
    break;
  }
  return locations;
}
