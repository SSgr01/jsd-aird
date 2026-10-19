import { FileTextOutlined, FormOutlined, LeftOutlined, RightOutlined } from '@ant-design/icons';
import { Button, Segmented, Tooltip, Typography } from 'antd';
import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent, PointerEvent as ReactPointerEvent, ReactNode } from 'react';
import { useCallback, useEffect, useRef, useState } from 'react';

const NARROW_WIDTH = 800;
const SOURCE_MIN_WIDTH = 320;
const RESULT_MIN_WIDTH = 420;
const DIVIDER_WIDTH = 8;
const DEFAULT_SOURCE_RATIO = 44;
const MIN_SOURCE_RATIO = 28;
const MAX_SOURCE_RATIO = 62;

type Pane = 'source' | 'result';

export function DocumentComparisonViewer({ original, result, originalTitle = '原文件', resultTitle = '解析结果', resultExtra }: { original: ReactNode; result: ReactNode; originalTitle?: string; resultTitle?: string; resultExtra?: ReactNode }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [narrow, setNarrow] = useState(false);
  const [active, setActive] = useState<Pane>('result');
  const [containerWidth, setContainerWidth] = useState(0);
  const [sourceRatio, setSourceRatio] = useState(DEFAULT_SOURCE_RATIO);
  const [focusedPane, setFocusedPane] = useState<Pane>();
  const [dragging, setDragging] = useState(false);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return undefined;

    if (typeof ResizeObserver === 'undefined') {
      const query = window.matchMedia('(max-width: 900px)');
      const apply = () => setNarrow(query.matches);
      apply();
      query.addEventListener('change', apply);
      return () => query.removeEventListener('change', apply);
    }

    const observer = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect.width ?? container.getBoundingClientRect().width;
      setContainerWidth(width);
      setNarrow(width <= NARROW_WIDTH);
    });
    observer.observe(container);
    return () => observer.disconnect();
  }, []);

  const ratioBounds = getRatioBounds(containerWidth);
  useEffect(() => {
    if (!containerWidth || narrow) return;
    setSourceRatio((current) => clamp(current, ratioBounds.min, ratioBounds.max));
  }, [containerWidth, narrow, ratioBounds.max, ratioBounds.min]);

  useEffect(() => {
    if (narrow) setFocusedPane(undefined);
  }, [narrow]);

  const resizeAt = useCallback((clientX: number) => {
    const bounds = containerRef.current?.getBoundingClientRect();
    if (!bounds) return;
    const usableWidth = bounds.width - DIVIDER_WIDTH;
    if (usableWidth <= 0) return;
    const next = ((clientX - bounds.left) / usableWidth) * 100;
    const limits = getRatioBounds(bounds.width);
    setSourceRatio(clamp(next, limits.min, limits.max));
  }, []);

  const startResize = (event: ReactPointerEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    setDragging(true);
    resizeAt(event.clientX);
  };

  const handleResizeKey = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Home') {
      event.preventDefault();
      setSourceRatio(clamp(DEFAULT_SOURCE_RATIO, ratioBounds.min, ratioBounds.max));
      return;
    }
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();
    const amount = event.shiftKey ? 8 : 3;
    setSourceRatio((current) => clamp(current + (event.key === 'ArrowLeft' ? -amount : amount), ratioBounds.min, ratioBounds.max));
  };

  if (narrow) {
    const showingSource = active === 'source';
    return <div ref={containerRef} className="knowledge-comparison knowledge-comparison--narrow">
      <Segmented block value={active} onChange={(value) => setActive(value as Pane)} options={[{ value: 'source', label: '原文件', icon: <FileTextOutlined /> }, { value: 'result', label: '解析结果', icon: <FormOutlined /> }]} />
      <ComparisonPane title={showingSource ? originalTitle : resultTitle} icon={showingSource ? <FileTextOutlined /> : <FormOutlined />} extra={showingSource ? undefined : resultExtra}>
        {showingSource ? original : result}
      </ComparisonPane>
    </div>;
  }

  const style = {
    '--knowledge-source-fr': `${sourceRatio}fr`,
    '--knowledge-result-fr': `${100 - sourceRatio}fr`,
  } as CSSProperties;
  const rootClassName = [
    'knowledge-comparison',
    focusedPane ? `knowledge-comparison--solo-${focusedPane}` : '',
    dragging ? 'knowledge-comparison--dragging' : '',
  ].filter(Boolean).join(' ');
  const restoreAction = <Button size="small" type="text" onClick={() => setFocusedPane(undefined)}>恢复双栏</Button>;

  const bounds = getRatioBounds(containerWidth);
  return <div ref={containerRef} className={rootClassName} style={style}>
    <ComparisonPane
      id="knowledge-comparison-source"
      title={originalTitle}
      icon={<FileTextOutlined />}
      hidden={focusedPane === 'result'}
      focusAction={focusedPane ? restoreAction : <Tooltip title="仅看解析结果"><Button size="small" type="text" aria-label="仅看解析结果" icon={<RightOutlined />} onClick={() => setFocusedPane('result')} /></Tooltip>}
    >{original}</ComparisonPane>
    {!focusedPane && <div
      className="knowledge-comparison-divider"
      role="separator"
      aria-label="调整原文件与解析结果的宽度"
      aria-orientation="vertical"
      aria-valuemin={Math.round(bounds.min)}
      aria-valuemax={Math.round(bounds.max)}
      aria-valuenow={Math.round(sourceRatio)}
      aria-valuetext={`原文件 ${Math.round(sourceRatio)}%`}
      aria-controls="knowledge-comparison-source knowledge-comparison-result"
      tabIndex={0}
      onPointerDown={startResize}
      onPointerMove={(event) => { if (dragging) resizeAt(event.clientX); }}
      onPointerUp={(event) => { if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId); setDragging(false); }}
      onPointerCancel={() => setDragging(false)}
      onLostPointerCapture={() => setDragging(false)}
      onDoubleClick={() => setSourceRatio(clamp(DEFAULT_SOURCE_RATIO, bounds.min, bounds.max))}
      onKeyDown={handleResizeKey}
    />}
    <ComparisonPane
      id="knowledge-comparison-result"
      title={resultTitle}
      icon={<FormOutlined />}
      extra={resultExtra}
      hidden={focusedPane === 'source'}
      focusAction={focusedPane ? restoreAction : <Tooltip title="仅看原文件"><Button size="small" type="text" aria-label="仅看原文件" icon={<LeftOutlined />} onClick={() => setFocusedPane('source')} /></Tooltip>}
    >{result}</ComparisonPane>
  </div>;
}

function ComparisonPane({ id, title, icon, extra, focusAction, hidden, children }: { id?: string; title: string; icon: ReactNode; extra?: ReactNode; focusAction?: ReactNode; hidden?: boolean; children: ReactNode }) {
  return <section id={id} className="knowledge-comparison-pane" hidden={hidden}>
    <header>
      <Typography.Text strong ellipsis={{ tooltip: title }}>{icon} {title}</Typography.Text>
      <div className="knowledge-comparison-pane-actions">{extra}{focusAction}</div>
    </header>
    <div className="knowledge-comparison-body">{children}</div>
  </section>;
}

function getRatioBounds(width: number) {
  if (!width) return { min: MIN_SOURCE_RATIO, max: MAX_SOURCE_RATIO };
  const usableWidth = Math.max(1, width - DIVIDER_WIDTH);
  return {
    min: Math.max(MIN_SOURCE_RATIO, (SOURCE_MIN_WIDTH / usableWidth) * 100),
    max: Math.min(MAX_SOURCE_RATIO, ((usableWidth - RESULT_MIN_WIDTH) / usableWidth) * 100),
  };
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}
