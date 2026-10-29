import { forwardRef, type ComponentPropsWithoutRef } from 'react';
import type { UniverSheetsEditor } from './UniverSheetsEditor';
import type { UniverDocsEditor } from './UniverDocsEditor';
import type { EditorHandle } from './types';
import { getDocsRuntime, getSheetsRuntime } from './editor-loader';

export const SheetsEditor = forwardRef<EditorHandle, ComponentPropsWithoutRef<typeof UniverSheetsEditor>>((props, ref) => {
  const Runtime = getSheetsRuntime();
  return <Runtime {...props} ref={ref} />;
});
export const DocsEditor = forwardRef<EditorHandle, ComponentPropsWithoutRef<typeof UniverDocsEditor>>((props, ref) => {
  const Runtime = getDocsRuntime();
  return <Runtime {...props} ref={ref} />;
});
