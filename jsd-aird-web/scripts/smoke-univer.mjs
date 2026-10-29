import assert from 'node:assert/strict';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { chromium } from 'playwright';
import { createServer } from 'vite';

const server = process.env.UNIVER_SMOKE_URL ? null : await createServer({ server: { host: '127.0.0.1', port: 5191, strictPort: true } });
if (server) await server.listen();
const base = process.env.UNIVER_SMOKE_URL || 'http://127.0.0.1:5191';
const executablePath = process.env.JSD_AIRD_RENDER_BROWSER_PATH || [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].find(existsSync);
const browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
const output = resolve('node_modules/.cache/univer-smoke');
mkdirSync(output, { recursive: true });

try {
  for (const format of ['sheets', 'docs']) {
    const errors = [];
    let stage = 'open';
    const page = await browser.newPage({ viewport: { width: 1500, height: 1000 } });
    page.on('pageerror', error => errors.push(`${stage}: ${error.stack || error.message}`));
    await page.goto(`${base}/scripts/univer-smoke.html?format=${format}`, { waitUntil: 'networkidle', timeout: 90000 });
    await page.waitForFunction(() => window.officeSmoke?.api && !document.querySelector('.univer-editor-host[aria-busy]'));
    await page.waitForTimeout(1000);
    assert.equal(await page.evaluate(() => window.officeSmoke.dirty), 0, 'initialization must not mark dirty');
    assert.equal(await page.evaluate(() => window.officeSmoke.api.executeCommand('ui.operation.open-replace-dialog')), true);
    await page.evaluate(() => window.officeSmoke.api.executeCommand('ui.operation.close-find-dialog'));

    const source = await page.evaluate(() => {
      const canvas = document.createElement('canvas'); canvas.width = 120; canvas.height = 80;
      const context = canvas.getContext('2d'); context.fillStyle = '#f97316'; context.fillRect(0, 0, 120, 80);
      context.fillStyle = 'white'; context.fillText('Office 1.2.0', 12, 40);
      return canvas.toDataURL('image/png');
    });
    await page.getByText('插入', { exact: true }).first().click();
    const chooser = page.waitForEvent('filechooser');
    await page.getByLabel('图片', { exact: true }).first().click();
    if (format === 'sheets') await page.getByText('浮动图片', { exact: true }).click();
    await (await chooser).setFiles({ name: 'office.png', mimeType: 'image/png', buffer: Buffer.from(source.split(',')[1], 'base64') });
    await page.waitForFunction(f => {
      const api = window.officeSmoke.api;
      return (f === 'docs' ? api.getActiveDocument() : api.getActiveWorkbook().getActiveSheet()).getImages().length === 1;
    }, format);

    stage = 'resize';
    await page.evaluate(async f => {
      const api = window.officeSmoke.api;
      const target = f === 'docs' ? api.getActiveDocument() : api.getActiveWorkbook().getActiveSheet();
      const image = target.getImages()[0];
      if (f === 'docs') {
        image.setSize(200, 120);
        image.setWrappingStyle(api.Enum.TextWrappingStyle.WRAP_SQUARE);
        image.setPositionH({ relativeFrom: 0, posOffset: 160 });
        image.setPositionV({ relativeFrom: 1, posOffset: 20 });
      } else {
        await image.setSizeAsync(200, 120);
        image.setPlacement({ kind: api.Enum.SheetDrawingAnchorType.Position,
          from: { row: 3, column: 2, rowOffset: 5, columnOffset: 10 }, width: 200, height: 120 });
        target.getRange('B2:B3').setNumberFormat('0.00');
        target.getRange('A1:B1').setFontWeight('bold');
        target.getRange('C2').setDataValidation(api.newDataValidation().requireValueInList(['甲', '乙']).build());
      }
    }, format);
    await page.waitForTimeout(300);
    assert.ok(await page.evaluate(() => window.officeSmoke.dirty) > 0);
    assert.equal(await page.evaluate(() => window.officeSmoke.discoveries), 0, 'image and formatting edits must not discover fields');
    if (format === 'sheets') await page.evaluate(() => window.officeSmoke.api.getActiveWorkbook()
      .getActiveSheet().getRange('B3').setValue('=SUM(B2,2)'));

    // Exercise the native clipboard handler with an actual image File.
    stage = 'paste';
    const canvas = page.locator(format === 'docs' ? '#univer-doc-main-canvas' : '[id^="univer-sheet-main-canvas_"]');
    await canvas.click({ position: { x: 500, y: 250 } });
    await page.evaluate(s => {
      const data = Uint8Array.from(atob(s.split(',')[1]), c => c.charCodeAt(0));
      const transfer = new DataTransfer(); transfer.items.add(new File([data], 'paste.png', { type: 'image/png' }));
      document.activeElement.dispatchEvent(new ClipboardEvent('paste', { bubbles: true, cancelable: true, clipboardData: transfer }));
    }, source);
    await page.waitForFunction(f => {
      const api = window.officeSmoke.api;
      return (f === 'docs' ? api.getActiveDocument() : api.getActiveWorkbook().getActiveSheet()).getImages().length === 2;
    }, format);

    assert.equal(await page.evaluate(() => window.officeSmoke.exports), 0, 'editing must not export a complete SDK snapshot');
    assert.ok(await page.evaluate(() => window.officeSmoke.revision()) > 0, 'real mutations must synchronously advance revision');
    const saved = await page.evaluate(() => window.officeSmoke.snapshot());
    assert.equal(await page.evaluate(() => window.officeSmoke.exports), 1, 'one explicit snapshot capture must export once');
    writeFileSync(resolve(output, `${format}-snapshot.json`), JSON.stringify(saved, null, 2) + '\n');
    await page.screenshot({ path: resolve(output, `${format}.png`) });
    stage = 'reopen';
    await page.evaluate(s => window.officeSmoke.restore(s), saved);
    await page.waitForFunction(f => {
      const api = window.officeSmoke.api;
      return !document.querySelector('.univer-editor-host[aria-busy]') &&
        (f === 'docs' ? api.getActiveDocument() : api.getActiveWorkbook().getActiveSheet()).getImages().length === 2;
    }, format);
    await page.waitForTimeout(1000);
    stage = 'undo-redo';
    assert.equal(await page.evaluate(() => window.officeSmoke.revision()), 0, 'restoring images, formulas and validation must not create a user revision');
    const counts = await page.evaluate(async f => {
      const api = window.officeSmoke.api;
      const target = f === 'docs' ? api.getActiveDocument() : api.getActiveWorkbook().getActiveSheet();
      const image = target.getImages()[0]; image.remove();
      const removed = target.getImages().length;
      await api.undo(); const undone = target.getImages().length;
      await api.redo(); const redone = target.getImages().length;
      return { removed, undone, redone };
    }, format);
    assert.deepEqual(counts, { removed: 1, undone: 2, redone: 1 });
    await page.evaluate(() => window.officeSmoke.flush());
    assert.ok(await page.evaluate(() => window.officeSmoke.revision()) >= 3, 'delete, undo and redo must each mark a persistent change');
    const afterDelete = await page.evaluate(() => window.officeSmoke.snapshot());
    await page.evaluate(snapshot => window.officeSmoke.restore(snapshot), afterDelete);
    await page.waitForFunction(f => {
      const api = window.officeSmoke.api;
      return !document.querySelector('.univer-editor-host[aria-busy]') && (f === 'docs' ? api.getActiveDocument() : api.getActiveWorkbook().getActiveSheet()).getImages().length === 1;
    }, format);
    await page.waitForTimeout(1000);
    assert.equal(await page.evaluate(() => window.officeSmoke.revision()), 0, 'reopening after delete must remain clean');
    if (format === 'sheets') {
      const selection = await page.evaluate(async () => {
        const api = window.officeSmoke.api, sheet = api.getActiveWorkbook().getActiveSheet();
        sheet.getRange('C20').setValue('=1+1'); await new Promise(done => setTimeout(done, 150));
        sheet.setActiveRange(sheet.getRange('C20')); window.officeSmoke.flush();
        const before = window.officeSmoke.revision();
        const commandStart = window.officeSmoke.commands.length;
        await api.executeCommand('formula.mutation.set-formula-calculation-result', { unitData: { 'smoke-book': { 'sheet-1': { 19: { 2: { v: 99, t: 2 } } } } }, unitOtherData: {} });
        const calculated = sheet.getRange('C20').getValue();
        sheet.setActiveRange(sheet.getRange('A1')); window.officeSmoke.flush();
        return { before, after: window.officeSmoke.revision(), calculated, commands: window.officeSmoke.commands.slice(commandStart).map(command => ({ id: command.id, options: command.options, type: command.type, trigger: command.params?.trigger })) };
      });
      assert.equal(selection.calculated, 99, 'inject a genuine formula result update');
      if (selection.before !== selection.after) console.log(JSON.stringify(selection));
      assert.equal(selection.before, selection.after, 'formula results and selection changes must not mark dirty');
      await page.evaluate(async () => {
        for (let index = 0; index < 2; index++) await window.officeSmoke.api.executeCommand('sheet.command.insert-row', {
          unitId: 'smoke-book', subUnitId: 'sheet-1', range: { startRow: 20, endRow: 20, startColumn: 0, endColumn: 19 }, direction: 0,
        });
        window.officeSmoke.flush();
      });
      assert.equal(await page.evaluate(() => window.officeSmoke.structureOperations.length), 2, 'batched structural operations must all be delivered');
      await page.evaluate(() => {
        const sheet = window.officeSmoke.api.getActiveWorkbook().getActiveSheet();
        sheet.setActiveRange(sheet.getRange('D5'));
      });
      await page.getByText('插入', { exact: true }).first().click();
      const cellChooser = page.waitForEvent('filechooser');
      await page.getByLabel('图片', { exact: true }).first().click();
      await page.getByText('单元格图片', { exact: true }).click();
      await (await cellChooser).setFiles({ name: 'cell.png', mimeType: 'image/png', buffer: Buffer.from(source.split(',')[1], 'base64') });
      await page.waitForFunction(() => window.officeSmoke.snapshot().sheets['sheet-1'].cellData[4]?.[3]?.p?.drawings);
      writeFileSync(resolve(output, 'sheets-cell-image.json'), JSON.stringify(await page.evaluate(() => window.officeSmoke.snapshot()), null, 2) + '\n');
    }
    assert.deepEqual(errors, [], 'editor must not throw browser errors');
    if (format === 'sheets') {
      await page.evaluate(() => { window.officeSmoke.api.getActiveWorkbook().getActiveSheet().getRange('J12:K12').setValues([['粘贴标签', 'value']]); window.officeSmoke.flush(); });
      assert.equal(await page.evaluate(() => window.officeSmoke.discoveryEvents.filter(event => event.selection.address === 'J12').length), 1, 'a pasted range must retain discovery of its anchor');
      assert.equal(await page.evaluate(() => window.officeSmoke.discoveryEvents.filter(event => event.selection.address === 'A1').length), 0, 'structure and selection changes must not rediscover an existing label');
    }
    stage = 'Chinese composition';
    if (format === 'sheets') {
      await page.evaluate(() => { const sheet = window.officeSmoke.api.getActiveWorkbook().getActiveSheet(); sheet.setActiveRange(sheet.getRange('G10')); });
      await page.keyboard.press('F2');
    } else {
      await canvas.click({ position: { x: 500, y: 250 } });
      await page.evaluate(() => window.officeSmoke.api.getActiveDocument().setSelection(0, 0));
    }
    const exportsBeforeComposition = await page.evaluate(() => window.officeSmoke.exports);
    const ime = await page.context().newCDPSession(page);
    await ime.send('Input.imeSetComposition', { text: '中文', selectionStart: 2, selectionEnd: 2 });
    await page.waitForTimeout(60);
    await ime.send('Input.imeSetComposition', { text: '中文输入', selectionStart: 4, selectionEnd: 4 });
    await page.waitForTimeout(60);
    await ime.send('Input.insertText', { text: '中文输入' });
    if (format === 'sheets') await page.keyboard.press('Enter');
    await page.evaluate(() => window.officeSmoke.flush());
    assert.equal(await page.evaluate(() => window.officeSmoke.exports), exportsBeforeComposition);
    if (format === 'sheets') {
      assert.equal(await page.evaluate(() => window.officeSmoke.api.getActiveWorkbook().getActiveSheet().getRange('G10').getValue()), '中文输入');
      assert.equal(await page.evaluate(() => window.officeSmoke.discoveryEvents.filter(event => event.selection.address === 'G10').length), 1, 'composition must discover a field only once');
    } else assert.equal(await page.evaluate(() => window.officeSmoke.snapshot().body.dataStream.split('中文输入').length - 1), 1);
    await ime.detach();
    console.log(`${format}: image upload, move/resize, paste, save/reopen, delete, undo/redo passed`);
    await page.close();

    const readonly = await browser.newPage({ viewport: { width: 1500, height: 1000 } });
    await readonly.goto(`${base}/scripts/univer-smoke.html?format=${format}&readonly`, { waitUntil: 'networkidle' });
    await readonly.waitForTimeout(1200);
    const beforeInput = await readonly.evaluate(f => {
      const snapshot = window.officeSmoke.snapshot();
      return JSON.stringify(f === 'docs' ? snapshot.body : snapshot.sheets['sheet-1'].cellData);
    }, format);
    await readonly.locator(format === 'docs' ? '#univer-doc-main-canvas' : '[id^="univer-sheet-main-canvas_"]')
      .click({ position: { x: 100, y: 140 } });
    await readonly.keyboard.type('READONLY');
    await readonly.keyboard.press('Delete');
    await readonly.waitForTimeout(150);
    const afterInput = await readonly.evaluate(f => {
      const snapshot = window.officeSmoke.snapshot();
      return JSON.stringify(f === 'docs' ? snapshot.body : snapshot.sheets['sheet-1'].cellData);
    }, format);
    assert.equal(afterInput, beforeInput, 'typing/deleting must not mutate a read-only document');
    assert.equal(await readonly.evaluate(() => window.officeSmoke.dirty), 0);
    await readonly.close();
  }
} finally {
  await browser.close();
  if (server) await server.close();
}
