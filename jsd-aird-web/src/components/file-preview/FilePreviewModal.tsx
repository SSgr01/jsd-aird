import { DownloadOutlined, FileOutlined, FullscreenExitOutlined, FullscreenOutlined, LoadingOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Modal, Spin, Table, Tabs, Tag, Tooltip, Typography, type TableColumnsType } from 'antd';
import { useEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';

import { downloadBlob, triggerNativeDownload } from '@/services/files';
import { buildSpreadsheetPreview, detectPreviewMode, excelColumnLabel, type FilePreviewDescriptor, type FilePreviewMode, type SpreadsheetCell, type SpreadsheetPreview } from './file-preview-utils';
import { PdfCanvasPreview } from './PdfCanvasPreview';
import './file-preview-modal.css';

const MAX_PREVIEW_BYTES = 50 * 1024 * 1024;
const MAX_ROWS = 200;
const MAX_COLUMNS = 50;

function hasPreviewPopup(dialog: HTMLElement) {
  const selector = [
    '.ant-tabs-dropdown:not(.ant-tabs-dropdown-hidden)',
    '.ant-dropdown:not(.ant-dropdown-hidden)',
    '.ant-select-dropdown:not(.ant-select-dropdown-hidden)',
    '.ant-picker-dropdown:not(.ant-picker-dropdown-hidden)',
    '.ant-popover:not(.ant-popover-hidden)', '.ant-modal-wrap', '[role="dialog"]',
  ].join(',');
  return Array.from(document.querySelectorAll<HTMLElement>(selector)).some(element =>
    !element.contains(dialog) && element.getClientRects().length > 0
      && getComputedStyle(element).visibility !== 'hidden' && !element.closest('[hidden], [aria-hidden="true"]'),
  );
}

interface FilePreviewModalProps {
  open: boolean;
  file?: FilePreviewDescriptor;
  onClose: () => void;
  showSpreadsheetMerges?: boolean;
}

interface SpreadsheetSheet {
  name: string;
  preview: SpreadsheetPreview;
}

interface SpreadsheetRow {
  key: string;
  rowNumber: number;
  cells: SpreadsheetCell[];
}

export function FilePreviewModal({ open, file, onClose, showSpreadsheetMerges = true }: FilePreviewModalProps) {
  const [maximized, setMaximized] = useState(false);
  const composing = useRef(false);
  const popupAtEscape = useRef(false);
  const [mode, setMode] = useState<FilePreviewMode>('unsupported');
  const [blob, setBlob] = useState<Blob>();
  const [objectUrl, setObjectUrl] = useState<string>();
  const [text, setText] = useState('');
  const [sheets, setSheets] = useState<SpreadsheetSheet[]>([]);
  const [activeSheet, setActiveSheet] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [docxRendering, setDocxRendering] = useState(false);
  const [error, setError] = useState<string>();
  const docxBodyRef = useRef<HTMLDivElement>(null);
  const docxStyleRef = useRef<HTMLDivElement>(null);

  const currentSheet = useMemo(
    () => sheets.find((sheet) => sheet.name === activeSheet) || sheets[0],
    [activeSheet, sheets],
  );

  useEffect(() => {
    let active = true;
    let nextObjectUrl: string | undefined;
    const load = async () => {
      if (!open || !file) return;
      setLoading(true);
      setError(undefined);
      setBlob(undefined);
      setObjectUrl(undefined);
      setText('');
      setSheets([]);
      setActiveSheet(undefined);
      if (docxBodyRef.current) docxBodyRef.current.replaceChildren();
      if (docxStyleRef.current) docxStyleRef.current.replaceChildren();
      const nextMode = detectPreviewMode(file.fileName, file.contentType);
      setMode(nextMode);
      try {
        if ((file.size || 0) > MAX_PREVIEW_BYTES) {
          throw new Error('文件超过 50 MB，暂不支持在线预览，请下载原文件查看。');
        }
        const loaded = await file.load();
        if (!active) return;
        if (loaded.size > MAX_PREVIEW_BYTES) {
          throw new Error('文件超过 50 MB，暂不支持在线预览，请下载原文件查看。');
        }
        setBlob(loaded);
        if (nextMode === 'image' || nextMode === 'audio' || nextMode === 'video') {
          nextObjectUrl = URL.createObjectURL(loaded);
          setObjectUrl(nextObjectUrl);
        } else if (nextMode === 'text') {
          setText(await loaded.text());
        } else if (nextMode === 'spreadsheet') {
          const XLSX = await import('xlsx');
          const workbook = XLSX.read(await loaded.arrayBuffer(), { type: 'array', cellDates: true });
          const parsedSheets = workbook.SheetNames.map((name) => {
            const worksheet = workbook.Sheets[name];
            if (!worksheet) return { name, preview: buildSpreadsheetPreview([]) };
            const rawRows = XLSX.utils.sheet_to_json<unknown[]>(worksheet, {
              header: 1,
              raw: false,
              defval: '',
            });
            const rows = rawRows.map((row) => row.map((cell) => {
              if (cell === null || cell === undefined) return '';
              if (typeof cell === 'string') return cell;
              if (typeof cell === 'number' || typeof cell === 'boolean' || typeof cell === 'bigint') return cell.toString();
              return JSON.stringify(cell) || '';
            }));
            return {
              name,
              preview: buildSpreadsheetPreview(
                rows,
                worksheet['!merges'] || [],
                worksheet['!ref'] ? XLSX.utils.decode_range(worksheet['!ref']).s.r : 0,
                worksheet['!ref'] ? XLSX.utils.decode_range(worksheet['!ref']).s.c : 0,
                MAX_ROWS,
                MAX_COLUMNS,
              ),
            };
          });
          setSheets(parsedSheets);
          setActiveSheet(parsedSheets[0]?.name);
        }
      } catch (reason) {
        if (active) setError(reason instanceof Error ? reason.message : '文件预览失败');
      } finally {
        if (active) setLoading(false);
      }
    };
    void load();
    return () => {
      active = false;
      if (nextObjectUrl) URL.revokeObjectURL(nextObjectUrl);
    };
  }, [file, open]);

  // The Word containers only mount after file loading has finished. Render
  // after that commit, and keep this effect independent of display mode.
  useEffect(() => {
    if (!open || loading || mode !== 'docx' || !blob || !docxBodyRef.current || !docxStyleRef.current) return;
    const body = docxBodyRef.current;
    const styles = docxStyleRef.current;
    let active = true;
    setDocxRendering(true);
    void import('docx-preview').then(({ renderAsync }) => {
      if (!active) return;
      return renderAsync(blob, body, styles, {
        breakPages: true,
        renderHeaders: true,
        renderFooters: true,
        renderFootnotes: true,
        renderEndnotes: true,
        useBase64URL: true,
      });
    }).catch((reason: unknown) => {
      if (active) setError(reason instanceof Error ? reason.message : 'Word 预览加载失败，请下载原文件查看。');
    }).finally(() => {
      if (active) setDocxRendering(false);
    });
    return () => { active = false; };
  }, [blob, loading, mode, open]);

  const download = async () => {
    if (!file) return;
    try {
      if (file.downloadUrl) {
        triggerNativeDownload(file.downloadUrl, file.fileName);
        return;
      }
      downloadBlob(blob || await file.load(), file.fileName);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '原文件下载失败');
    }
  };

  const tableColumns: TableColumnsType<SpreadsheetRow> = currentSheet
    ? [
        {
          title: '#',
          dataIndex: 'rowNumber',
          key: 'row-number',
          width: 56,
          fixed: 'left',
        },
        ...Array.from({ length: Math.max(1, currentSheet.preview.columnCount) }, (_, index) => ({
          title: excelColumnLabel(currentSheet.preview.startColumn + index),
          key: `column-${index}`,
          onCell: (row: SpreadsheetRow) => {
            const cell = row.cells[index];
            return cell?.hidden ? { rowSpan: 0, colSpan: 0 } : { rowSpan: cell?.rowSpan || 1, colSpan: cell?.colSpan || 1 };
          },
          render: (_: unknown, row: SpreadsheetRow) => {
            const cell = row.cells[index];
            return cell?.hidden ? null : cell?.value || '—';
          },
        })),
      ]
    : [];
  const tableRows: SpreadsheetRow[] = currentSheet?.preview.rows.map((row, index) => ({ key: `${currentSheet.name}-${index}`, rowNumber: row.rowNumber, cells: row.cells })) || [];

  const handleEscape = (event: ReactKeyboardEvent<HTMLElement>) => {
    if (event.key !== 'Escape') return;
    // Child popups and native media fullscreen consume Escape first.
    if (event.defaultPrevented || event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229
      || composing.current || document.fullscreenElement) {
      event.stopPropagation();
      return;
    }
    // Tab overflow menus close from a window listener. Let that listener run
    // without also restoring or closing this modal on the same key.
    if (popupAtEscape.current) return;
    event.preventDefault();
    event.stopPropagation();
    if (maximized) setMaximized(false);
    else onClose();
  };

  return (
    <Modal
      open={open}
      title={<div className="file-preview-header">
        <span className="file-preview-title" title={file?.fileName}>
          <FileOutlined aria-hidden /><span>{file?.fileName || '文件预览'}</span>
        </span>
        <Tooltip title={maximized ? '还原预览（Esc）' : '全屏预览'}>
          <Button type="text" className="file-preview-maximize" aria-label={maximized ? '还原预览' : '全屏预览'}
            aria-pressed={maximized} icon={maximized ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
            onClick={() => setMaximized(value => !value)}>{maximized ? '还原' : '全屏'}</Button>
        </Tooltip>
      </div>}
      width="min(960px, calc(100vw - 32px))"
      footer={<Button icon={<DownloadOutlined />} onClick={() => void download()} disabled={!file}>下载原文件</Button>}
      destroyOnHidden
      onCancel={onClose}
      afterClose={() => { setMaximized(false); composing.current = false; }}
      closable={{ 'aria-label': '关闭文件预览' }}
      keyboard={false}
      className={`file-preview-modal${maximized ? ' file-preview-modal-maximized' : ''}`}
      wrapProps={{ onKeyDownCapture: (event: ReactKeyboardEvent<HTMLDivElement>) => {
        if (event.key !== 'Escape') return;
        const dialog = event.currentTarget.querySelector<HTMLElement>('.file-preview-dialog');
        popupAtEscape.current = hasPreviewPopup(dialog || event.currentTarget);
        // Ant Design's initial focus sentinel sits outside modalRender.
        if (!(event.target instanceof Element) || !event.target.closest('.file-preview-dialog')) handleEscape(event);
      } }}
      modalRender={(node) => <div className="file-preview-dialog"
        onCompositionStart={() => { composing.current = true; }}
        onCompositionEnd={() => { composing.current = false; }}
        onKeyDown={handleEscape}>{node}</div>}
    >
      {!file ? <Empty description="未选择文件" /> : loading ? (
        <div className="file-preview-state"><Spin indicator={<LoadingOutlined spin />} /><Typography.Text type="secondary">正在生成预览…</Typography.Text></div>
      ) : error ? (
        <div className="file-preview-state"><Alert type="warning" showIcon message={error} /><Button icon={<DownloadOutlined />} onClick={() => void download()}>下载原文件</Button></div>
      ) : mode === 'pdf' && blob ? (
        <PdfCanvasPreview blob={blob} />
      ) : mode === 'image' && objectUrl ? (
        <div className="file-preview-image-wrap"><img className="file-preview-image" src={objectUrl} alt={file.fileName} /></div>
      ) : mode === 'audio' && objectUrl ? (
        <div className="file-preview-state"><audio controls preload="metadata" src={objectUrl} style={{ width: '100%' }} onError={() => setError('浏览器无法播放此音频编码，可下载原文件查看。')}>当前浏览器不支持音频播放</audio></div>
      ) : mode === 'video' && objectUrl ? (
        <div className="file-preview-state"><video className="file-preview-video" controls preload="metadata" src={objectUrl} onError={() => setError('浏览器无法播放此视频编码，可下载原文件查看。')}>当前浏览器不支持视频播放</video></div>
      ) : mode === 'text' ? (
        <pre className="file-preview-text">{text || '文件内容为空'}</pre>
      ) : mode === 'docx' ? (
        <div className="file-preview-docx" aria-busy={docxRendering}>
          {docxRendering && <div className="file-preview-docx-loading"><Spin /><Typography.Text type="secondary">正在排版文档…</Typography.Text></div>}
          <div ref={docxStyleRef} /><div ref={docxBodyRef} />
        </div>
      ) : mode === 'spreadsheet' && sheets.length ? (
        <div className="file-preview-spreadsheet">
          <Tabs activeKey={activeSheet} onChange={setActiveSheet} items={sheets.map((sheet) => ({ key: sheet.name, label: sheet.name }))} />
          {currentSheet?.preview.truncated && <Tag color="warning">仅展示前 200 行、前 50 列</Tag>}
          {showSpreadsheetMerges && currentSheet?.preview.merges.length ? <div className="file-preview-merges"><Typography.Text type="secondary">已识别合并单元格</Typography.Text><div>{currentSheet.preview.merges.map((merge) => <Tag key={merge.range}>{merge.range}{merge.clipped ? '（预览内裁剪）' : ''}</Tag>)}</div></div> : null}
          <Table<SpreadsheetRow> size="small" bordered pagination={false} scroll={{ x: 'max-content', y: '50vh' }} columns={tableColumns} dataSource={tableRows} />
        </div>
      ) : (
        <div className="file-preview-state"><Empty description="当前文件格式不支持在线预览" /><Button icon={<DownloadOutlined />} onClick={() => void download()}>下载原文件</Button></div>
      )}
    </Modal>
  );
}
