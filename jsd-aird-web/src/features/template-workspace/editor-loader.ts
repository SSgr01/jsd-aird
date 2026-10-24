import { lazy } from 'react';

const sheetsImport = () => import('./UniverSheetsEditor').then(module => ({ default: module.UniverSheetsEditor }));
const docsImport = () => import('./UniverDocsEditor').then(module => ({ default: module.UniverDocsEditor }));
let sheets: ReturnType<typeof sheetsImport> | undefined;
let docs: ReturnType<typeof docsImport> | undefined;
let sheetsFailed = false;
let docsFailed = false;
const loadSheets = () => sheets ??= sheetsImport().catch((error: unknown) => { sheetsFailed = true; throw error; });
const loadDocs = () => docs ??= docsImport().catch((error: unknown) => { docsFailed = true; throw error; });
let sheetsRuntime = lazy(loadSheets);
let docsRuntime = lazy(loadDocs);

export function getSheetsRuntime() { return sheetsRuntime; }
export function getDocsRuntime() { return docsRuntime; }
export function preloadEditorRuntime(format: string) { return /^(DOCX|word)$/i.test(format) ? loadDocs() : loadSheets(); }
export function resetFailedEditors() {
  if (sheetsFailed) { sheets = undefined; sheetsFailed = false; sheetsRuntime = lazy(loadSheets); }
  if (docsFailed) { docs = undefined; docsFailed = false; docsRuntime = lazy(loadDocs); }
}
