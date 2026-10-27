import ExcelJS from 'exceljs';
import type { Shipment } from '@/services/inventory/inventory-api';

const headings = ['客户名', '品名', '批次号', '规格', '数量', '总数量', '快递公司', '备注'];
const widths = [18, 24, 22, 14, 14, 16, 20, 30];

export function createShipmentSheetWorkbook(date: string, details: Shipment[]): ExcelJS.Workbook {
  const workbook = new ExcelJS.Workbook();
  const sheet = workbook.addWorksheet('出货记录', {
    pageSetup: { orientation: 'landscape', fitToPage: true, fitToWidth: 1, fitToHeight: 0 },
  });
  sheet.columns = widths.map((width) => ({ width }));
  sheet.mergeCells('A1:H1');
  sheet.getCell('A1').value = '出货记录表';
  sheet.getCell('A1').font = { name: 'Microsoft YaHei', size: 16 };
  sheet.getRow(1).height = 32;

  sheet.getCell('A2').value = '日期';
  sheet.getCell('B2').value = date;
  sheet.mergeCells('C2:H2');
  sheet.getRow(2).height = 25;

  headings.forEach((heading, index) => { sheet.getCell(3, index + 1).value = heading; });
  sheet.getRow(3).height = 26;

  for (let index = 0; index < Math.max(12, details.length); index += 1) {
    const detail = details[index];
    const row = sheet.getRow(index + 4);
    if (detail) row.values = [
      detail.customerName || '', detail.productName || '', detail.batchNo || '',
      detail.specification || '', detail.packageQuantity ?? '',
      detail.totalQuantityKg ?? detail.quantityKg, detail.courierCompany || '', detail.note || '',
    ];
    row.height = detail ? 38 : 25;
  }

  for (let rowIndex = 1; rowIndex <= Math.max(12, details.length) + 3; rowIndex += 1) {
    for (let columnIndex = 1; columnIndex <= 8; columnIndex += 1) {
      const cell = sheet.getCell(rowIndex, columnIndex);
      cell.font = rowIndex === 1
        ? { name: 'Microsoft YaHei', size: 16 }
        : { name: 'Microsoft YaHei', size: 10 };
      cell.alignment = { horizontal: 'center', vertical: 'middle', wrapText: true };
      cell.border = {
        top: { style: 'thin', color: { argb: 'FFABB5B0' } },
        left: { style: 'thin', color: { argb: 'FFABB5B0' } },
        bottom: { style: 'thin', color: { argb: 'FFABB5B0' } },
        right: { style: 'thin', color: { argb: 'FFABB5B0' } },
      };
    }
  }
  sheet.pageSetup.printArea = `A1:H${Math.max(12, details.length) + 3}`;
  return workbook;
}

export async function exportShipmentSheet(shipmentNo: string, date: string, details: Shipment[]): Promise<void> {
  const workbook = createShipmentSheetWorkbook(date, details);
  const data = await workbook.xlsx.writeBuffer();
  const blob = new Blob([data], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `出货明细-${shipmentNo.replace(/[\\/:*?"<>|]/g, '_')}.xlsx`;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
