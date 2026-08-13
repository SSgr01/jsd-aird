import { DownloadOutlined, FileOutlined, LoadingOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Modal, Spin, Table, Tabs, Tag, Typography, type TableColumnsType } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';

import { downloadBlob } from '@/services/files';
import { buildSpreadsheetPreview, detectPreviewMode, excelColumnLabel, type FilePreviewDescriptor, type FilePreviewMode, type SpreadsheetCell, type SpreadsheetPreview } from './file-preview-utils';

const MAX_PREVIEW_BYTES = 50 * 1024 * 1024;
const MAX_ROWS = 200;
const MAX_COLUMNS = 50;

interface FilePreviewModalProps {
  open: boolean;
  file?: FilePreviewDescriptor;
  onClose: () => void;
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

export function FilePreviewModal({ open, file, onClose }: FilePreviewModalProps) {
  const [mode, setMode] = useState<FilePreviewMode>('unsupported');
  const [blob, setBlob] = useState<Blob>();
  const [objectUrl, setObjectUrl] = useState<string>();
  const [text, setText] = useState('');
  const [sheets, setSheets] = useState<SpreadsheetSheet[]>([]);
  const [activeSheet, setActiveSheet] = useState<string>();
  const [loading, setLoading] = useState(false);
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
        if (nextMode === 'pdf' || nextMode === 'image' || nextMode === 'audio') {
          nextObjectUrl = URL.createObjectURL(loaded);
          setObjectUrl(nextObjectUrl);
        } else if (nextMode === 'text') {
          setText(await loaded.text());
        } else if (nextMode === 'docx') {
          if (!docxBodyRef.current || !docxStyleRef.current) return;
          const { renderAsync } = await import('docx-preview');
          await renderAsync(loaded, docxBodyRef.current, docxStyleRef.current, {
            breakPages: true,
            renderHeaders: true,
            renderFooters: true,
            renderFootnotes: true,
            renderEndnotes: true,
            useBase64URL: true,
          });
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

  const download = async () => {
    if (!file) return;
    try {
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

  return (
    <Modal
      open={open}
      title={<span><FileOutlined /> {file?.fileName || '文件预览'}</span>}
      width="min(960px, calc(100vw - 32px))"
      footer={<Button icon={<DownloadOutlined />} onClick={() => void download()} disabled={!file}>下载原文件</Button>}
      destroyOnHidden
      onCancel={onClose}
      className="file-preview-modal"
    >
      {!file ? <Empty description="未选择文件" /> : loading ? (
        <div className="file-preview-state"><Spin indicator={<LoadingOutlined spin />} /><Typography.Text type="secondary">正在生成预览…</Typography.Text></div>
      ) : error ? (
        <div className="file-preview-state"><Alert type="warning" showIcon message={error} /><Button icon={<DownloadOutlined />} onClick={() => void download()}>下载原文件</Button></div>
      ) : mode === 'pdf' && objectUrl ? (
        <iframe className="file-preview-frame" title={`${file.fileName} 预览`} src={objectUrl} />
      ) : mode === 'image' && objectUrl ? (
        <div className="file-preview-image-wrap"><img className="file-preview-image" src={objectUrl} alt={file.fileName} /></div>
      ) : mode === 'audio' && objectUrl ? (
        <div className="file-preview-state"><audio controls preload="metadata" src={objectUrl} style={{ width: '100%' }}>当前浏览器不支持音频播放</audio></div>
      ) : mode === 'text' ? (
        <pre className="file-preview-text">{text || '文件内容为空'}</pre>
      ) : mode === 'docx' ? (
        <div className="file-preview-docx"><div ref={docxStyleRef} /><div ref={docxBodyRef} /></div>
      ) : mode === 'spreadsheet' && sheets.length ? (
        <div className="file-preview-spreadsheet">
          <Tabs activeKey={activeSheet} onChange={setActiveSheet} items={sheets.map((sheet) => ({ key: sheet.name, label: sheet.name }))} />
          {currentSheet?.preview.truncated && <Tag color="warning">仅展示前 200 行、前 50 列</Tag>}
          {currentSheet?.preview.merges.length ? <div className="file-preview-merges"><Typography.Text type="secondary">已识别合并单元格</Typography.Text><div>{currentSheet.preview.merges.map((merge) => <Tag key={merge.range}>{merge.range}{merge.clipped ? '（预览内裁剪）' : ''}</Tag>)}</div></div> : null}
          <Table<SpreadsheetRow> size="small" bordered pagination={false} scroll={{ x: 'max-content', y: '50vh' }} columns={tableColumns} dataSource={tableRows} />
        </div>
      ) : (
        <div className="file-preview-state"><Empty description="当前文件格式不支持在线预览" /><Button icon={<DownloadOutlined />} onClick={() => void download()}>下载原文件</Button></div>
      )}
    </Modal>
  );
}
