import assert from 'node:assert/strict';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { chromium } from 'playwright';
import { createServer } from 'vite';

const server = await createServer({ server: { host: '127.0.0.1', port: 5193, strictPort: true } });
await server.listen();
const executablePath = ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(existsSync);
const browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
const output = resolve('node_modules/.cache/unified-lab'); mkdirSync(output, { recursive: true });
try {
  for (const kind of ['test', 'synthesis']) {
    const page = await browser.newPage({ viewport: { width: 1550, height: 1050 } });
    const errors = []; page.on('pageerror', e => errors.push(e.stack || e.message));
    await page.goto('http://127.0.0.1:5193/scripts/univer-smoke.html?format=sheets', { waitUntil: 'networkidle', timeout: 90000 });
    await page.waitForFunction(() => window.officeSmoke?.api && !document.querySelector('.univer-editor-host[aria-busy]'));
    const fixture = JSON.parse(readFileSync(resolve(`../jsd-aird-api/target/unified-lab/${kind}-example.snapshot.json`), 'utf8'));
    await page.evaluate(snapshot => window.officeSmoke.restore(snapshot), fixture);
    await page.waitForFunction(() => !document.querySelector('.univer-editor-host[aria-busy]') && window.officeSmoke.api.getActiveWorkbook().getActiveSheet().getCustomMetadata()?.labTemplate);
    await page.waitForTimeout(500);
    const initial = await page.evaluate(() => window.officeSmoke.snapshot());
    const initialSheet = Object.values(initial.sheets)[0];
    const fieldRow = (s, code) => Number(Object.entries(s.cellData).find(([, cells]) => cells[1]?.custom?.lab?.fieldCode === code)?.[0]);
    const total = fieldRow(initialSheet, 'formula.total');
    await page.evaluate(total => window.officeSmoke.api.getActiveWorkbook().getActiveSheet().insertRowsBefore(total, 2), total);
    await page.evaluate(() => window.officeSmoke.structure.appendRecords('comparison', {count:2}));
    let snapshot = await page.evaluate(() => window.officeSmoke.snapshot()); let sheet = Object.values(snapshot.sheets)[0];
    let instance = sheet.custom.templateInstance;
    assert.equal(fieldRow(sheet, 'formula.total'), total + 2);
    assert.equal(instance.records.length, 8); assert.equal(new Set(instance.records.map(r=>r.id)).size, 8);
    assert.equal(instance.items.length, Object.values(initial.sheets)[0].custom.templateInstance.items.length + 2);
    await page.evaluate(({total}) => {
      const s=window.officeSmoke.api.getActiveWorkbook().getActiveSheet();
      s.getRange(`B${total+1}`).setValue('MAT-ADDED-01');s.getRange(`B${total+2}`).setValue('MAT-ADDED-02');
      for(let c=2;c<=9;c++){const col=String.fromCharCode(65+c);s.getRange(`${col}${total+1}`).setValue('90g');s.getRange(`${col}${total+2}`).setValue('/');}
      const projection=s.getCustomMetadata().templateInstance.execution.listProjections[0];
      const first=Number(projection.labelRange.match(/[0-9]+/)[0])-1;
      for(let r=first;r<total;r++){s.getRange(`I${r+1}`).setValue('/');s.getRange(`J${r+1}`).setValue('/');}
    },{total});
    await page.waitForFunction(row => window.officeSmoke.api.getActiveWorkbook().getActiveSheet().getRange(`I${row+1}`).getValue()===90,total+2,{timeout:20000});
    const metricRows=instance.model.fields.filter(f=>f.groupId===(kind==='test'?'film':'results')).map(f=>Number(instance.mapping.find(b=>b.bindingId===f.bindingId)?.locator.labelRange?.match(/[0-9]+/)[0]??0));
    const localRow=Math.max(...metricRows);
    await page.evaluate(row=>window.officeSmoke.api.getActiveWorkbook().getActiveSheet().insertRowsBefore(row,1),localRow);
    const local=await page.evaluate(({kind,row})=>window.officeSmoke.structure.addField({kind:'REPEAT_FIELD',name:'临时耐湿热观察',parentBindingId:'comparison',groupId:kind==='test'?'film':'results',labelRange:`B${row+1}`,valueRange:`C${row+1}:J${row+1}`,experimentField:{domain:'TEST',field:'VALUE'}}),{kind,row:localRow});
    snapshot=await page.evaluate(()=>window.officeSmoke.snapshot());sheet=Object.values(snapshot.sheets)[0];
    await page.evaluate(()=>window.officeSmoke.api.undo());
    let undone=Object.values((await page.evaluate(()=>window.officeSmoke.snapshot())).sheets)[0];
    assert.ok(!undone.custom.templateInstance.model.fields.some(f=>f.id===local.id));
    assert.equal(undone.rowCount,sheet.rowCount,'field creation and native row insertion are separate user actions');
    await page.evaluate(()=>window.officeSmoke.api.undo());
    undone=Object.values((await page.evaluate(()=>window.officeSmoke.snapshot())).sheets)[0];
    assert.equal(undone.rowCount,sheet.rowCount-1);
    await page.evaluate(async()=>{await window.officeSmoke.api.redo();await window.officeSmoke.api.redo();});
    await page.evaluate(row=>window.officeSmoke.api.getActiveWorkbook().getActiveSheet().getRange(`I${row+1}`).setValue('无变化'),localRow);
    const saved = await page.evaluate(() => window.officeSmoke.snapshot());
    assert.equal(Object.values(saved.sheets)[0].rowCount, sheet.rowCount, 'redo must restore the row exactly once');
    writeFileSync(resolve(output, `${kind}-expanded.json`), JSON.stringify(saved));
    await page.evaluate(s => window.officeSmoke.restore(s), saved);
    await page.waitForFunction(() => !document.querySelector('.univer-editor-host[aria-busy]') && window.officeSmoke.api.getActiveWorkbook().getActiveSheet().getCustomMetadata()?.labTemplate);
    await page.waitForTimeout(300);
    const reopened = Object.values((await page.evaluate(() => window.officeSmoke.snapshot())).sheets)[0];
    assert.equal(reopened.cellData[localRow][8].v, '无变化');
    assert.equal(reopened.custom.templateInstance.records[7].id, sheet.custom.templateInstance.records[7].id);
    await page.screenshot({ path: resolve(output, `${kind}.png`) });
    assert.deepEqual(errors, []); console.log(`${kind}: PASS — +2 materials, 8 groups, g formula, local field, undo/redo and reopen`);
    await page.close();
  }
} finally { await browser.close(); await server.close(); }
