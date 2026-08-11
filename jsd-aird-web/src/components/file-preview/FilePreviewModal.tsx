import { DownloadOutlined, FileOutlined, LoadingOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Modal, Spin, Table, Tabs, Tag, Typography, type TableColumnsType } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';

import { downloadBlob } from '@/services/files';
import { detectPreviewMode, type FilePreviewDescriptor, type FilePreviewMode } from './file-preview-utils';

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
  rows: string[][];
  truncated: boolean;
}

interface SpreadsheetRow {
  key: string;
  [key: string]: string;
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
        if (nextMode === 'pdf' || nextMode === 'image') {
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
            if (!worksheet) return { name, rows: [], truncated: false };
            const rawRows = XLSX.utils.sheet_to_json<unknown[]>(worksheet, {
              header: 1,
              raw: false,
              defval: '',
            });
            const rows = rawRows.slice(0, MAX_ROWS).map((row) => row.slice(0, MAX_COLUMNS).map((cell) => {
              if (cell === null || cell === undefined) return '';
              if (typeof cell === 'string') return cell;
              if (typeof cell === 'number' || typeof cell === 'boolean' || typeof cell === 'bigint') return cell.toString();
              return JSON.stringify(cell) || '';
            }));
            return {
              name,
              rows,
              truncated: rawRows.length > MAX_ROWS || rawRows.some((row) => row.length > MAX_COLUMNS),
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
    ? Array.from({ length: Math.max(1, Math.min(MAX_COLUMNS, currentSheet.rows.reduce((max, row) => Math.max(max, row.length), 0))) }, (_, index) => ({
        title: `列 ${index + 1}`,
        dataIndex: `column-${index}`,
        key: `column-${index}`,
        render: (_: unknown, row: SpreadsheetRow) => row[`column-${index}`] || '—',
      }))
    : [];
  const tableRows: SpreadsheetRow[] = currentSheet?.rows.map((row, index) => ({ key: `${currentSheet.name}-${index}`, ...Object.fromEntries(row.map((cell, cellIndex) => [`column-${cellIndex}`, cell])) })) || [];

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
      ) : mode === 'text' ? (
        <pre className="file-preview-text">{text || '文件内容为空'}</pre>
      ) : mode === 'docx' ? (
        <div className="file-preview-docx"><div ref={docxStyleRef} /><div ref={docxBodyRef} /></div>
      ) : mode === 'spreadsheet' && sheets.length ? (
        <div className="file-preview-spreadsheet">
          <Tabs activeKey={activeSheet} onChange={setActiveSheet} items={sheets.map((sheet) => ({ key: sheet.name, label: sheet.name }))} />
          {currentSheet?.truncated && <Tag color="warning">仅展示前 200 行、前 50 列</Tag>}
          <Table<SpreadsheetRow> size="small" bordered pagination={false} scroll={{ x: 'max-content', y: '50vh' }} columns={tableColumns} dataSource={tableRows} />
        </div>
      ) : (
        <div className="file-preview-state"><Empty description="当前文件格式不支持在线预览" /><Button icon={<DownloadOutlined />} onClick={() => void download()}>下载原文件</Button></div>
      )}
    </Modal>
  );
}
