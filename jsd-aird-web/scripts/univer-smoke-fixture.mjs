import React from 'react';
import { createRoot } from 'react-dom/client';
import { UniverSheetsEditor } from '/src/features/template-workspace/UniverSheetsEditor.tsx';
import { UniverDocsEditor } from '/src/features/template-workspace/UniverDocsEditor.tsx';
import { FUniver, ICommandService } from '@univerjs/presets';

const params = new URLSearchParams(location.search);
const docs = params.get('format') === 'docs';
const editable = !params.has('readonly');
const ref = React.createRef();
const blank = docs
  ? { id: 'smoke-doc', snapshotFormatVersion: 5, editorMode: 'UNIVER_DOCS', title: '图片验证',
      body: { dataStream: '图片验证\r\n', paragraphs: [{ startIndex: 4 }] } }
  : { id: 'smoke-book', name: '图片验证', sheetOrder: ['sheet-1'],
      sheets: { 'sheet-1': { id: 'sheet-1', name: 'Sheet1', rowCount: 100, columnCount: 20,
        cellData: { 0: { 0: { v: '项目' }, 1: { v: '数值' } }, 1: { 0: { v: '甲' }, 1: { v: 3 } } } } } };
const root = createRoot(document.getElementById('root'));
let mount = 0;
window.officeSmoke = {
  dirty: 0, discoveries: 0, discoveryEvents: [], commands: [], structureOperations: [],
  exports: 0,
  revision: () => ref.current.getContentRevision(),
  flush: () => ref.current.flushChanges(),
  snapshot: () => ref.current.getSnapshot(),
  restore: snapshot => render(snapshot),
};
// Capture the production editor's API only in this isolated development fixture.
for (const method of ['createWorkbook', 'createDocument']) {
  const original = FUniver.prototype[method];
  FUniver.prototype[method] = function (...args) {
    window.officeSmoke.api = this;
    const result = original.apply(this, args);
    const prototype = Object.getPrototypeOf(result);
    if (!prototype.__smokeSave) {
      const save = prototype.save;
      prototype.save = function (...values) { window.officeSmoke.exports++; return save.apply(this, values); };
      prototype.__smokeSave = true;
    }
    this._injector.get(ICommandService).onCommandExecuted((event, options) => window.officeSmoke.commands.push({ id: event.id, params: event.params, options, type: event.type }));
    return result;
  };
}
function render(snapshot) {
  root.render(React.createElement(docs ? UniverDocsEditor : UniverSheetsEditor, {
    key: ++mount, snapshot, editable, bindings: [], ref,
    onDirty: () => window.officeSmoke.dirty++,
    onEditorValue: () => undefined,
    onStructureChange: operation => window.officeSmoke.structureOperations.push(operation),
    onUnboundCellChange: (selection, value) => { window.officeSmoke.discoveries++; window.officeSmoke.discoveryEvents.push({ selection, value }); },
  }));
}
render(blank);
