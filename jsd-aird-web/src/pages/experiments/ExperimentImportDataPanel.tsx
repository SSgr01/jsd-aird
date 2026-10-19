import { Button, Empty, Tag, Typography } from 'antd';
import { useMemo, useState } from 'react';

interface SheetSummary {
  id: string;
  name: string;
  count: number;
  samples: Array<{ address: string; value: string }>;
}

function columnName(index: number): string {
  let value = index + 1;
  let name = '';
  while (value > 0) {
    value -= 1;
    name = String.fromCharCode(65 + value % 26) + name;
    value = Math.floor(value / 26);
  }
  return name;
}

function summarize(snapshot?: Record<string, unknown>): SheetSummary[] {
  const sheets = snapshot?.sheets;
  if (!sheets || typeof sheets !== 'object' || Array.isArray(sheets)) return [];
  return Object.entries(sheets).map(([id, rawSheet]) => {
    const sheet = rawSheet as Record<string, unknown>;
    const rows = (sheet.cellData ?? {}) as Record<string, Record<string, { v?: unknown }>>;
    const samples: SheetSummary['samples'] = [];
    let count = 0;
    for (const [row, cells] of Object.entries(rows)) {
      for (const [column, cell] of Object.entries(cells ?? {})) {
        const value = cell?.v;
        if (value == null || String(value).trim() === '') continue;
        count += 1;
        if (samples.length < 80) samples.push({
          address: `${columnName(Number(column))}${Number(row) + 1}`,
          value: String(value),
        });
      }
    }
    return { id, name: String(sheet.name || id), count, samples };
  });
}

export function ExperimentImportDataPanel({ snapshot, confirmed, editable, busy, onConfirm }: {
  snapshot?: Record<string, unknown>;
  confirmed: boolean;
  editable: boolean;
  busy: boolean;
  onConfirm: () => void;
}) {
  const sheets = useMemo(() => summarize(snapshot), [snapshot]);
  const [selectedId, setSelectedId] = useState<string>();
  const selected = sheets.find((sheet) => sheet.id === selectedId) ?? sheets[0];
  const total = sheets.reduce((sum, sheet) => sum + sheet.count, 0);
  return <aside className="data-workbench-panel experiment-template-data-panel experiment-import-data-panel">
    <div className="data-workbench-panel-header">
      <div className="data-workbench-panel-title">
        <Typography.Title level={5}>本次实验数据</Typography.Title>
        <Tag color={confirmed ? 'success' : 'warning'}>{confirmed ? '已确认' : '待确认'}</Tag>
      </div>
      <Typography.Text type="secondary">{sheets.length} 个工作表 · {total} 个非空单元格</Typography.Text>
    </div>
    <div className="data-panel-body experiment-import-data-body">
      <div className="experiment-import-sheet-tabs" role="tablist" aria-label="工作表">
        {sheets.map((sheet) => <button type="button" role="tab" key={sheet.id}
          aria-selected={selected?.id === sheet.id}
          className={selected?.id === sheet.id ? 'active' : ''}
          onClick={() => setSelectedId(sheet.id)}>{sheet.name}</button>)}
      </div>
      {selected?.samples.length ? <div className="experiment-import-values">
        {selected.samples.map((item) => <div key={item.address} className="experiment-import-value">
          <span>{item.address}</span><span title={item.value}>{item.value}</span>
        </div>)}
        {selected.count > selected.samples.length ? <Typography.Text type="secondary">
          其余 {selected.count - selected.samples.length} 个单元格请在左侧工作簿核对。
        </Typography.Text> : null}
      </div> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="此工作表没有数据" />}
    </div>
    <div className="data-workbench-footer">
      <Button type="primary" disabled={!editable || !total || confirmed} loading={busy}
        onClick={onConfirm}>确认并提交本次数据</Button>
    </div>
  </aside>;
}
