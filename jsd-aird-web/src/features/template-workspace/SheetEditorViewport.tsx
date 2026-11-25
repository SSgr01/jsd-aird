import type { ReactNode } from 'react';
import { WorkspaceFrame, WorkspaceMaximizeButton } from '@/components/workspace/WorkspaceFrame';
import './sheet-editor-viewport.css';

/** Shared geometry and fullscreen boundary; keep the workbook mounted in place. */
export function SheetEditorViewport({ children, fullscreenEnabled = false }: {
  children: ReactNode;
  fullscreenEnabled?: boolean;
}) {
  return <WorkspaceFrame maximizable={fullscreenEnabled} className="sheet-editor-viewport"
    data-fullscreen-enabled={fullscreenEnabled} aria-label="表格工作区">
    {fullscreenEnabled && <div className="sheet-editor-viewport-toolbar"><WorkspaceMaximizeButton targetLabel="表格" /></div>}
    {children}
  </WorkspaceFrame>;
}
