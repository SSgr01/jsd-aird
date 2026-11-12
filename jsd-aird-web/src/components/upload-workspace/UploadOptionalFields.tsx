import type { ReactNode } from 'react';
import { useDesktopLayout } from '@/hooks/use-desktop-layout';

/** Optional metadata stays mounted and keeps its values when collapsed. */
export function UploadOptionalFields({ children, summary }: { children: ReactNode; summary: string }) {
  const desktop = useDesktopLayout();
  if (!desktop) return children;
  return <details className="upload-workspace-optional">
    <summary>{summary}<span>选填</span></summary>
    <div className="upload-workspace-optional-fields">{children}</div>
  </details>;
}
