import fs from 'node:fs/promises';
import path from 'node:path';
import { FileBlob, SpreadsheetFile } from '@oai/artifact-tool';

const inputDir = path.resolve(process.argv[2]);
const outputDir = path.resolve(process.argv[3] ?? path.join(inputDir, 'previews'));
await fs.mkdir(outputDir, { recursive: true });
const files = (await fs.readdir(inputDir)).filter((name) => name.endsWith('.xlsx')).sort();
const result = [];
for (const name of files) {
  const stat = await fs.stat(path.join(inputDir, name));
  if (stat.size < 100) {
    result.push({ name, status: 'intentionally-invalid', sheets: [] });
    continue;
  }
  let workbook;
  try {
    workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(path.join(inputDir, name)));
  } catch (error) {
    result.push({ name, status: 'intentionally-invalid', error: String(error), sheets: [] });
    continue;
  }
  const sheets = [];
  for (let index = 0; index < workbook.worksheets.items.length; index += 1) {
    const sheet = workbook.worksheets.getItemAt(index);
    const safe = `${name.replace(/\.xlsx$/i, '')}-${String(index + 1).padStart(2, '0')}`;
    const preview = await workbook.render({ sheetName: sheet.name, autoCrop: 'all', scale: 1, format: 'png' });
    await fs.writeFile(path.join(outputDir, `${safe}.png`), new Uint8Array(await preview.arrayBuffer()));
    const inspect = await workbook.inspect({ kind: 'table', sheetId: sheet.name, tableMaxRows: 12, tableMaxCols: 12, maxChars: 4000 });
    await fs.writeFile(path.join(outputDir, `${safe}.inspect.ndjson`), inspect.ndjson ?? '');
    sheets.push(sheet.name);
  }
  result.push({ name, sheets });
}
await fs.writeFile(path.join(outputDir, 'verification.json'), JSON.stringify(result, null, 2));
console.log(JSON.stringify({ inputDir, outputDir, files: result }, null, 2));
