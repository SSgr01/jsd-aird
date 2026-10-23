/** Start the selected editor while its snapshot/review requests are in flight.
 * Dynamic imports share the browser's module cache with React.lazy. A failed
 * preload is handled by the normal lazy boundary when the editor is rendered.
 */
export function preloadWorkspaceEditor(format: 'DOCX' | 'XLSX' | 'word' | 'excel' | 'WORD' | 'EXCEL') {
  const loading = format === 'DOCX' || format === 'word' || format === 'WORD'
    ? import('./UniverDocsEditor')
    : import('./UniverSheetsEditor');
  void loading.catch(() => undefined);
}
