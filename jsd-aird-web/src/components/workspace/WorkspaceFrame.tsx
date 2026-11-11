import { FullscreenExitOutlined, FullscreenOutlined } from '@ant-design/icons';
import { Button, Tooltip } from 'antd';
import { createContext, forwardRef, useContext, useEffect, useRef, useState, type HTMLAttributes } from 'react';
import './workspace-frame.css';

const WorkspaceContext = createContext<{ maximized: boolean; toggle: () => void } | null>(null);

// Popups are portalled by Ant Design and Univer. Read their presence before
// their Escape handlers run, so closing a popup never also restores the page.
const overlaySelector = [
  '.ant-modal-wrap', '.ant-drawer-open',
  '.ant-select-dropdown:not(.ant-select-dropdown-hidden)',
  '.ant-dropdown:not(.ant-dropdown-hidden)',
  '.ant-picker-dropdown:not(.ant-picker-dropdown-hidden)',
  '.ant-popover:not(.ant-popover-hidden)',
  '[role="dialog"]', '[role="listbox"]', '[role="menu"]:not(.ant-menu-root)',
].join(',');

function hasOpenOverlay() {
  return Array.from(document.querySelectorAll<HTMLElement>(overlaySelector)).some((element) =>
    element.getClientRects().length > 0 && getComputedStyle(element).visibility !== 'hidden'
      && !element.closest('[hidden], [aria-hidden="true"]'),
  );
}

export interface WorkspaceFrameProps extends HTMLAttributes<HTMLElement> {
  /** Explicitly enabled only for experiment and document workspaces. */
  maximizable?: boolean;
}

/** A stable section: maximizing changes CSS, never the editor's React tree. */
export const WorkspaceFrame = forwardRef<HTMLElement, WorkspaceFrameProps>(function WorkspaceFrame(
  { maximizable = false, className = '', children, ...props }, ref,
) {
  const [maximized, setMaximized] = useState(false);
  const composing = useRef(false);
  const active = maximizable && maximized;

  useEffect(() => {
    if (!active) return;
    const bodyOverflow = document.body.style.overflow;
    const rootOverflow = document.documentElement.style.overflow;
    document.body.style.overflow = 'hidden';
    document.documentElement.style.overflow = 'hidden';
    document.body.classList.add('workspace-is-maximized');
    let overlayAtKeydown = false;
    const startComposition = () => { composing.current = true; };
    const endComposition = () => { composing.current = false; };
    const captureEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') overlayAtKeydown = hasOpenOverlay();
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented || event.isComposing
        || event.keyCode === 229 || composing.current || overlayAtKeydown) return;
      event.preventDefault();
      setMaximized(false);
    };
    window.addEventListener('compositionstart', startComposition, true);
    window.addEventListener('compositionend', endComposition, true);
    window.addEventListener('keydown', captureEscape, true);
    window.addEventListener('keydown', escape);
    return () => {
      document.body.style.overflow = bodyOverflow;
      document.documentElement.style.overflow = rootOverflow;
      document.body.classList.remove('workspace-is-maximized');
      composing.current = false;
      window.removeEventListener('compositionstart', startComposition, true);
      window.removeEventListener('compositionend', endComposition, true);
      window.removeEventListener('keydown', captureEscape, true);
      window.removeEventListener('keydown', escape);
    };
  }, [active]);

  return <WorkspaceContext.Provider value={maximizable ? { maximized: active, toggle: () => setMaximized(value => !value) } : null}>
    <section {...props} ref={ref} className={`workspace-frame ${className}`} data-maximized={active || undefined}>
      {children}
    </section>
  </WorkspaceContext.Provider>;
});

/** Place last in the existing toolbar. Renders nothing without explicit opt-in. */
export function WorkspaceMaximizeButton() {
  const workspace = useContext(WorkspaceContext);
  if (!workspace) return null;
  const label = workspace.maximized ? '还原' : '全屏';
  return <Tooltip title={workspace.maximized ? '还原工作区（Esc）' : '全屏工作区'}>
    <Button className="workspace-maximize-button" aria-label={label} aria-pressed={workspace.maximized}
      icon={workspace.maximized ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
      onMouseDown={(event) => event.preventDefault()} onClick={workspace.toggle}>{label}</Button>
  </Tooltip>;
}
