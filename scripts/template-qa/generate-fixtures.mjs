import fs from 'node:fs/promises';
import path from 'node:path';
import { Workbook, SpreadsheetFile } from '@oai/artifact-tool';
import {
  AlignmentType,
  BorderStyle,
  Document,
  Footer,
  Header,
  HeadingLevel,
  ImageRun,
  Packer,
  PageBreak,
  Paragraph,
  Table,
  TableCell,
  TableRow,
  TextRun,
  WidthType,
} from 'docx';

const outputDir = path.resolve(process.argv[2] ?? 'artifacts/template-qa/fixtures');
await fs.mkdir(outputDir, { recursive: true });

const headerFill = '#DBEAFE';
const border = { style: 'thin', color: '#CBD5E1' };

function styleSheet(sheet, range, headerRows = 1) {
  sheet.showGridLines = false;
  sheet.getRange(range).format = {
    font: { name: 'Aptos', size: 10, color: '#0F172A' },
    verticalAlignment: 'center',
  };
  if (headerRows > 0) {
    const match = range.match(/^([A-Z]+)(\d+):([A-Z]+)(\d+)$/);
    if (match) {
      const [, startCol, startRow, endCol] = match;
      const endHeaderRow = Number(startRow) + headerRows - 1;
      sheet.getRange(`${startCol}${startRow}:${endCol}${endHeaderRow}`).format = {
        fill: headerFill,
        font: { name: 'Aptos', size: 10, bold: true, color: '#1E3A8A' },
        horizontalAlignment: 'center',
        verticalAlignment: 'center',
        borders: { preset: 'all', style: 'thin', color: '#93C5FD' },
      };
    }
  }
  sheet.getRange(range).format.borders = { preset: 'outside', style: 'thin', color: border.color };
}

async function saveWorkbook(name, builder) {
  const workbook = Workbook.create();
  await builder(workbook);
  const output = await SpreadsheetFile.exportXlsx(workbook);
  await output.save(path.join(outputDir, name));
}

await saveWorkbook('simple-form.xlsx', async (wb) => {
  const sheet = wb.worksheets.add('表单');
  sheet.getRange('A1:D1').merge();
  sheet.getRange('A1').values = [['材料基础信息']];
  sheet.getRange('A2:B6').values = [
    ['物料名称', 'TEST-TPL-丙烯酸树脂'],
    ['牌号', 'A-2026'],
    ['供应商', '杰事达供应商'],
    ['密度', 1.05],
    ['检验日期', new Date('2026-08-11')],
  ];
  sheet.getRange('A8:D10').values = [
    ['备注', null, null, null],
    ['适用温度', '25 ℃', null, null],
    ['状态', '合格', null, null],
  ];
  sheet.getRange('A8:D8').merge();
  sheet.getRange('A8').values = [['备注']];
  sheet.getRange('A1:D10').format.wrapText = true;
  sheet.getRange('B5').format.numberFormat = '0.00';
  sheet.getRange('B6').format.numberFormat = 'yyyy-mm-dd';
  styleSheet(sheet, 'A1:D10', 0);
  sheet.getRange('A1:D1').format = { fill: '#2563EB', font: { name: 'Aptos Display', size: 14, bold: true, color: '#FFFFFF' }, horizontalAlignment: 'center' };
  sheet.getRange('A2:A6').format = { fill: '#EFF6FF', font: { name: 'Aptos', size: 10, bold: true, color: '#1E3A8A' } };
  sheet.getRange('A1:D10').format.columnWidth = 18;
  sheet.getRange('A1:D10').format.rowHeight = 22;
  sheet.getRange('A1:D1').format.rowHeight = 30;
});

await saveWorkbook('simple-row-table.xlsx', async (wb) => {
  const sheet = wb.worksheets.add('原料长表');
  const rows = [['物料名称', '牌号', '供应商', '批次', '含量', '密度']];
  for (let i = 1; i <= 12; i += 1) {
    rows.push([`TEST-原料-${String(i).padStart(2, '0')}`, `G-${100 + i}`, `供应商-${i % 3 + 1}`, `LOT-202608${String(i).padStart(2, '0')}`, 0.85 + i / 1000, 0.98 + i / 100]);
  }
  sheet.getRange(`A1:F${rows.length}`).values = rows;
  sheet.getRange(`E2:E${rows.length}`).format.numberFormat = '0.0%';
  sheet.getRange(`F2:F${rows.length}`).format.numberFormat = '0.00';
  sheet.getRange(`A1:F${rows.length}`).format.columnWidth = 18;
  sheet.getRange(`A1:F${rows.length}`).format.rowHeight = 21;
  styleSheet(sheet, `A1:F${rows.length}`, 1);
  sheet.freezePanes.freezeRows(1);
});

await saveWorkbook('column-table.xlsx', async (wb) => {
  const sheet = wb.worksheets.add('横向列表');
  sheet.getRange('A1:E1').values = [['属性', '样品A', '样品B', '样品C', '样品D']];
  sheet.getRange('A2:E7').values = [
    ['物料名称', '树脂A', '树脂B', '树脂C', '树脂D'],
    ['供应商', '供应商1', '供应商2', '供应商1', '供应商3'],
    ['固含量', 0.55, 0.6, 0.58, 0.62],
    ['粘度', 1200, 900, 1100, 980],
    ['密度', 1.05, 1.02, 1.04, 1.03],
    ['检验日期', new Date('2026-08-01'), new Date('2026-08-02'), new Date('2026-08-03'), new Date('2026-08-04')],
  ];
  sheet.getRange('C1:C7').format.fill = '#F8FAFC';
  sheet.getRange('B4:E4').format.numberFormat = '0.0%';
  sheet.getRange('B7:E7').format.numberFormat = 'yyyy-mm-dd';
  sheet.getRange('A1:E7').format.columnWidth = 18;
  styleSheet(sheet, 'A1:E7', 1);
});

await saveWorkbook('matrix-cross-table.xlsx', async (wb) => {
  const sheet = wb.worksheets.add('交叉表');
  sheet.getRange('A1:F1').merge();
  sheet.getRange('A1').values = [['不同温度与配方组合的粘度测试']];
  sheet.getRange('A2:E2').values = [['温度 / 配方', '配方A', '配方B', '配方C', '配方D']];
  sheet.getRange('A3:A6').values = [['25℃'], ['40℃'], ['60℃'], ['80℃']];
  sheet.getRange('B3:E6').values = [
    [1200, 1300, 1450, 1500],
    [980, 1100, 1250, 1380],
    [760, 850, 960, 1050],
    [620, 700, 810, 900],
  ];
  sheet.getRange('A2:E6').format.columnWidth = 18;
  sheet.getRange('A2:E6').format.borders = { preset: 'all', style: 'thin', color: '#CBD5E1' };
  sheet.getRange('A2:E2').format = { fill: '#DBEAFE', font: { bold: true, color: '#1E3A8A' }, horizontalAlignment: 'center' };
  sheet.getRange('A1:F1').format = { fill: '#2563EB', font: { size: 13, bold: true, color: '#FFFFFF' }, horizontalAlignment: 'center' };
});

await saveWorkbook('multi-row-header.xlsx', async (wb) => {
  const sheet = wb.worksheets.add('多行表头');
  sheet.getRange('A1:F1').merge();
  sheet.getRange('A1').values = [['原料性能复合表']];
  sheet.getRange('A2:F2').values = [['基础信息', '基础信息', '供应信息', '供应信息', '检测结果', '检测结果']];
  sheet.getRange('A3:F3').values = [['物料名称', '牌号', '供应商', '批次', '固含量', '检验日期']];
  sheet.getRange('A4:F8').values = [
    ['树脂A', 'A-01', '供应商1', 'L01', 0.55, new Date('2026-08-01')],
    ['树脂B', 'B-02', '供应商2', 'L02', 0.6, new Date('2026-08-02')],
    ['树脂C', 'C-03', '供应商1', 'L03', 0.58, new Date('2026-08-03')],
    ['树脂D', 'D-04', '供应商3', 'L04', 0.62, new Date('2026-08-04')],
    ['树脂E', 'E-05', '供应商3', 'L05', 0.59, new Date('2026-08-05')],
  ];
  sheet.getRange('A2:B2').merge(true);
  sheet.getRange('C2:D2').merge(true);
  sheet.getRange('E2:F2').merge(true);
  sheet.getRange('E4:E8').format.numberFormat = '0.0%';
  sheet.getRange('F4:F8').format.numberFormat = 'yyyy-mm-dd';
  sheet.getRange('A1:F8').format.columnWidth = 17;
  sheet.getRange('A2:F3').format = { fill: '#DBEAFE', font: { bold: true, color: '#1E3A8A' }, horizontalAlignment: 'center' };
  sheet.getRange('A1:F1').format = { fill: '#2563EB', font: { size: 13, bold: true, color: '#FFFFFF' }, horizontalAlignment: 'center' };
});

await saveWorkbook('no-header.xlsx', async (wb) => {
  const sheet = wb.worksheets.add('无表头');
  sheet.getRange('A1:D8').values = [
    ['TEST-001', '供应商1', 0.55, 1.02],
    ['TEST-002', '供应商2', 0.6, 1.03],
    ['TEST-003', '供应商3', 0.58, 1.04],
    ['TEST-004', '供应商1', 0.62, 1.05],
    ['TEST-005', '供应商2', 0.59, 1.01],
    ['TEST-006', '供应商3', 0.57, 1.02],
    ['TEST-007', '供应商1', 0.61, 1.03],
    ['TEST-008', '供应商2', 0.56, 1.04],
  ];
  sheet.getRange('A1:D8').format.columnWidth = 18;
});

await saveWorkbook('formula-and-total.xlsx', async (wb) => {
  const sheet = wb.worksheets.add('公式合计');
  sheet.getRange('A1:D7').values = [
    ['物料', '数量', '单价', '金额'],
    ['材料A', 10, 12.5, null],
    ['材料B', 8, 15, null],
    ['材料C', 12, 9.8, null],
    ['', '', '', ''],
    ['小计', null, null, null],
    ['合计', null, null, null],
  ];
  sheet.getRange('D2:D4').formulas = [['=B2*C2'], ['=B3*C3'], ['=B4*C4']];
  sheet.getRange('D6').formulas = [['=SUM(D2:D4)']];
  sheet.getRange('D7').formulas = [['=D6']];
  sheet.getRange('A1:D7').format.columnWidth = 17;
  styleSheet(sheet, 'A1:D7', 1);
});

await saveWorkbook('unknown-and-duplicate.xlsx', async (wb) => {
  const sheet = wb.worksheets.add('冲突字段');
  sheet.getRange('A1:F6').values = [
    ['物料名称', '物料名称', '未知属性', '供应商', '供应商', '备注'],
    ['树脂A', '树脂A-重复', '未知值', '供应商1', '供应商1-重复', '需人工判断'],
    ['树脂B', '树脂B-重复', '未知值2', '供应商2', '供应商2-重复', ''],
    ['树脂C', '树脂C-重复', '未知值3', '供应商3', '供应商3-重复', ''],
    ['树脂D', '树脂D-重复', '未知值4', '供应商1', '供应商1-重复', ''],
    ['树脂E', '树脂E-重复', '未知值5', '供应商2', '供应商2-重复', ''],
  ];
  sheet.getRange('A1:F6').format.columnWidth = 18;
  styleSheet(sheet, 'A1:F6', 1);
});

await saveWorkbook('multi-sheet.xlsx', async (wb) => {
  const valid = wb.worksheets.add('有效长表');
  valid.getRange('A1:D5').values = [
    ['物料名称', '牌号', '供应商', '含量'],
    ['树脂A', 'A-01', '供应商1', 0.55],
    ['树脂B', 'B-02', '供应商2', 0.6],
    ['树脂C', 'C-03', '供应商3', 0.58],
    ['树脂D', 'D-04', '供应商1', 0.62],
  ];
  styleSheet(valid, 'A1:D5', 1);
  const empty = wb.worksheets.add('空Sheet');
  empty.getRange('A1').values = [['本Sheet无有效数据']];
  const matrix = wb.worksheets.add('矩阵Sheet');
  matrix.getRange('A1:D4').values = [
    ['温度/配方', '配方A', '配方B', '配方C'],
    ['25℃', 1, 2, 3],
    ['40℃', 4, 5, 6],
    ['60℃', 7, 8, 9],
  ];
  styleSheet(matrix, 'A1:D4', 1);
});

await saveWorkbook('merged-cells.xlsx', async (wb) => {
  const sheet = wb.worksheets.add('合并单元格');
  sheet.getRange('A1:F1').merge();
  sheet.getRange('A1').values = [['合并区域标题']];
  sheet.getRange('A2:B2').merge();
  sheet.getRange('A2').values = [['物料基础信息']];
  sheet.getRange('A3:A5').merge();
  sheet.getRange('A3').values = [['样品信息']];
  sheet.getRange('B3:F5').values = [
    ['物料名称', '树脂A', '牌号', 'A-01', '供应商1'],
    ['批次', 'LOT-001', '含量', 0.55, '合格'],
    ['日期', new Date('2026-08-11'), '密度', 1.05, '备注'],
  ];
  sheet.getRange('B5').format.numberFormat = 'yyyy-mm-dd';
  sheet.getRange('D4').format.numberFormat = '0.0%';
  sheet.getRange('A1:F5').format.columnWidth = 18;
  sheet.getRange('A1:F5').format.borders = { preset: 'all', style: 'thin', color: '#CBD5E1' };
  sheet.getRange('A1:F1').format = { fill: '#2563EB', font: { size: 13, bold: true, color: '#FFFFFF' }, horizontalAlignment: 'center' };
  sheet.getRange('A2:F2').format = { fill: '#DBEAFE', font: { bold: true, color: '#1E3A8A' } };
});

await saveWorkbook('unit-and-type.xlsx', async (wb) => {
  const sheet = wb.worksheets.add('类型单位');
  sheet.getRange('A1:H7').values = [
    ['名称', '日期', '整数', '小数', '百分比', '金额', '布尔', '枚举'],
    ['记录1', new Date('2026-08-11'), 10, 1.25, 0.55, 1234.5, true, '合格'],
    ['记录2', new Date('2026-08-12'), 20, 2.5, 0.6, 2345.6, false, '待复核'],
    ['记录3', new Date('2026-08-13'), 30, 3.75, 0.58, 3456.7, true, '不合格'],
    ['记录4', new Date('2026-08-14'), 40, 4.0, 0.62, 4567.8, true, '合格'],
    ['记录5', new Date('2026-08-15'), 50, 5.5, 0.59, 5678.9, false, '待复核'],
    ['记录6', new Date('2026-08-16'), 60, 6.25, 0.57, 6789.1, true, '合格'],
  ];
  sheet.getRange('B2:B7').format.numberFormat = 'yyyy-mm-dd';
  sheet.getRange('E2:E7').format.numberFormat = '0.0%';
  sheet.getRange('F2:F7').format.numberFormat = '#,##0.00';
  sheet.getRange('A1:H7').format.columnWidth = 16;
  styleSheet(sheet, 'A1:H7', 1);
});

const tinyPng = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64');

function wordHeaderFooter() {
  return {
    headers: { default: new Header({ children: [new Paragraph({ children: [new TextRun('TEST-TPL Word 模板页眉')] })] }) },
    footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER, children: [new TextRun('TEST-TPL 页脚')] })] }) },
  };
}

function docParagraph(text, heading) {
  return new Paragraph({ heading, children: [new TextRun(text)] });
}

async function saveDocx(name, children, options = {}) {
  const doc = new Document({
    sections: [{ properties: {}, children, ...(options.headers ? wordHeaderFooter() : {}) }],
  });
  const buffer = await Packer.toBuffer(doc);
  await fs.writeFile(path.join(outputDir, name), buffer);
}

await saveDocx('word-basic.docx', [
  docParagraph('TEST-TPL Word 基础模板', HeadingLevel.TITLE),
  docParagraph('一级章节', HeadingLevel.HEADING_1),
  docParagraph('这是一级章节正文，包含中文、English 和数字 123。'),
  docParagraph('二级章节', HeadingLevel.HEADING_2),
  docParagraph('二级章节正文。'),
  docParagraph('三级章节', HeadingLevel.HEADING_3),
  docParagraph('三级章节正文。'),
  docParagraph('四级章节', HeadingLevel.HEADING_4),
  docParagraph('四级章节正文。'),
  docParagraph('五级章节', HeadingLevel.HEADING_5),
  docParagraph('五级章节正文。'),
  docParagraph('六级章节', HeadingLevel.HEADING_6),
  docParagraph('六级章节正文。'),
  new Paragraph({ children: [] }),
]);

const borderConfig = { top: { style: BorderStyle.SINGLE, size: 1, color: 'CBD5E1' }, bottom: { style: BorderStyle.SINGLE, size: 1, color: 'CBD5E1' }, left: { style: BorderStyle.SINGLE, size: 1, color: 'CBD5E1' }, right: { style: BorderStyle.SINGLE, size: 1, color: 'CBD5E1' } };
const table = new Table({
  width: { size: 100, type: WidthType.PERCENTAGE },
  rows: [
    ['字段', '值', '状态'].map((cell) => new TableCell({ borders: borderConfig, children: [new Paragraph({ children: [new TextRun({ text: cell, bold: true })] })] })),
    ['物料名称', '树脂A', '合格'].map((cell) => new TableCell({ borders: borderConfig, children: [new Paragraph({ children: [new TextRun(cell)] })] })),
    ['供应商', '供应商1', '已确认'].map((cell) => new TableCell({ borders: borderConfig, children: [new Paragraph({ children: [new TextRun(cell)] })] })),
  ].map((cells) => new TableRow({ children: cells })),
});
await saveDocx('word-list-table.docx', [
  docParagraph('列表与表格模板', HeadingLevel.HEADING_1),
  docParagraph('有序实验步骤', HeadingLevel.HEADING_2),
  new Paragraph({ numbering: { reference: 'numbered-list', level: 0 }, children: [new TextRun('准备原料')] }),
  new Paragraph({ numbering: { reference: 'numbered-list', level: 0 }, children: [new TextRun('执行混合')] }),
  new Paragraph({ numbering: { reference: 'numbered-list', level: 0 }, children: [new TextRun('记录结果')] }),
  docParagraph('无序检查清单', HeadingLevel.HEADING_2),
  new Paragraph({ bullet: { level: 0 }, children: [new TextRun('检查温度')] }),
  new Paragraph({ bullet: { level: 0 }, children: [new TextRun('检查粘度')] }),
  new Paragraph({ bullet: { level: 0 }, children: [new TextRun('检查外观')] }),
  docParagraph('普通表格', HeadingLevel.HEADING_2),
  table,
]);

await saveDocx('word-page-break.docx', [
  docParagraph('分页前章节', HeadingLevel.HEADING_1),
  docParagraph('分页前正文。'),
  new Paragraph({ children: [new PageBreak()] }),
  docParagraph('分页后章节', HeadingLevel.HEADING_1),
  docParagraph('分页后正文。'),
]);

await saveDocx('word-header-footer.docx', [
  docParagraph('页眉页脚模板', HeadingLevel.HEADING_1),
  docParagraph('正文用于验证页眉、页脚和章节结构是否保留。'),
  table,
], { headers: true });

await saveDocx('word-image.docx', [
  docParagraph('图片混合模板', HeadingLevel.HEADING_1),
  docParagraph('图片前正文。'),
  new Paragraph({ children: [new ImageRun({ data: tinyPng, transformation: { width: 80, height: 80 }, type: 'png' })] }),
  docParagraph('图片后正文。'),
  table,
]);

await fs.writeFile(path.join(outputDir, 'invalid-corrupt.xlsx'), Buffer.from('this is not an xlsx file', 'utf8'));
await fs.writeFile(path.join(outputDir, 'empty.xlsx'), Buffer.alloc(0));
await fs.writeFile(path.join(outputDir, 'unsupported.txt'), 'TEST-TPL unsupported file', 'utf8');
await fs.writeFile(path.join(outputDir, 'fixture-manifest.json'), JSON.stringify({
  prefix: 'TEST-TPL',
  generatedAt: new Date().toISOString(),
  files: (await fs.readdir(outputDir)).sort(),
}, null, 2));

console.log(JSON.stringify({ outputDir, files: await fs.readdir(outputDir) }, null, 2));
