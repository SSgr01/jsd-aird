import React from 'react';
import { createRoot } from 'react-dom/client';
import { UniverSheetsEditor } from '/src/features/template-workspace/UniverSheetsEditor.tsx';
import { UniverDocsEditor } from '/src/features/template-workspace/UniverDocsEditor.tsx';
import { FUniver, ICommandService, IUndoRedoService } from '@univerjs/presets';
import { labBindings } from '/src/features/experiment-workspace/lab-template.ts';
import { labWorkbookInstance } from '/src/features/experiment-workspace/lab-structure-adapter.ts';
import { readWorkbookInstance } from '/src/features/template-workspace/workbook-instance.ts';
import { createCustomFieldWorkspace } from '/src/features/template-workspace/custom-field-operations.ts';

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
  structure: {
    instance: () => ref.current.getTemplateInstance(),
    appendRecords: (bindingId,options) => ref.current.appendRepeatRecord(ref.current.getTemplateInstance().mapping.find(b=>b.bindingId===bindingId),options),
    addField: async input => {
      const instance=ref.current.getTemplateInstance();
      const parent=instance.model.fields.find(f=>f.bindingId===input.parentBindingId);
      const created=createCustomFieldWorkspace({},instance.model,instance.mapping,{...input,ownerId:'smoke',origin:'INSTANCE_LOCAL',parentField:parent,parentBinding:instance.mapping.find(b=>b.bindingId===input.parentBindingId)});
      created.model.fields=created.model.fields.map(f=>f.id===created.field.id?{...f,experimentField:input.experimentField}:f);
      created.mapping=created.mapping.map(b=>b.bindingId===created.binding.bindingId?{...b,experimentField:input.experimentField}:b);
      await ref.current.setTemplateInstance({...instance,model:created.model,mapping:created.mapping});
      return created.field;
    },
    undoItem: () => window.officeSmoke.api._injector.get(IUndoRedoService).pitchTopUndoElement(),
  },
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
    key: ++mount, snapshot, editable, bindings: labBindings(snapshot), instance: readWorkbookInstance(snapshot) ?? labWorkbookInstance(snapshot), ref,
    onDirty: () => window.officeSmoke.dirty++,
    onEditorValue: () => undefined,
    onStructureChange: operation => window.officeSmoke.structureOperations.push(operation),
    onUnboundCellChange: (selection, value) => { window.officeSmoke.discoveries++; window.officeSmoke.discoveryEvents.push({ selection, value }); },
  }));
}
render(blank);
