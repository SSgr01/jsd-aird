import { Alert, Tag, Typography } from 'antd';

import type { ExperimentSourceReference } from '@/services/experiments/experiment-api';

interface SourceGroup {
  key: string;
  label: string;
  refs: ExperimentSourceReference[];
}

export function ExperimentSourcePanel({
  references,
  fallbackFileName,
}: {
  references: ExperimentSourceReference[];
  fallbackFileName?: string;
}) {
  if (!references.length && !fallbackFileName) return null;

  const groups = groupReferences(references);
  const conflicts = groups.filter((group) => new Set(group.refs
    .map(sourceIdentity)
    .filter((identity): identity is string => Boolean(identity))).size > 1);

  return (
    <details className="experiment-source-details">
      <summary>来源文件与记录位置 <span>{references.length || 1}</span></summary>
      <div className="experiment-source-content">
        <Typography.Text type="secondary">
          来源记录按业务编号归组；不同工作表仍分别列出，原始字段和值不会在此处合并或改写。
        </Typography.Text>
        {conflicts.map((group) => <Alert
          key={`conflict-${group.key}`}
          type="warning"
          showIcon
          message={`“${group.label}”的来源标识不一致`}
          description="同一逻辑样品的来源记录包含不同标识，请核对各工作表原文。系统保留所有原始记录。"
        />)}
        {!references.length ? <div className="experiment-source-row">{fallbackFileName} · 来源位置未提供</div> : null}
        {groups.map((group) => <section className="experiment-source-group" key={group.key}>
          <header>
            <strong>{group.label}</strong>
            <Tag>{group.refs.length} 条来源记录</Tag>
          </header>
          <ul>
            {group.refs.map((ref) => <li key={ref.id}>
              <span>{ref.sourceFileName || fallbackFileName || '来源文件'}</span>
              <span className="experiment-source-location">{sourceLocation(ref)}</span>
              <Tag color={ref.sourceOwner === 'EXPERIMENT' ? 'purple' : 'blue'}>
                {ref.sourceOwner === 'EXPERIMENT' ? '实验本导入' : '数据中心导入'}
              </Tag>
            </li>)}
          </ul>
        </section>)}
      </div>
    </details>
  );
}

function groupReferences(references: ExperimentSourceReference[]): SourceGroup[] {
  const groups: SourceGroup[] = [];
  const keyToGroup = new Map<string, SourceGroup>();
  references.forEach((ref, index) => {
    const identity = sourceIdentity(ref);
    const logicalSample = ref.logicalSampleKey?.trim();
    const fallback = ref.sampleBoundaryId || `来源记录 ${index + 1}`;
    const matchKeys = [
      identity && `${ref.sourceFileId}|identity:${identity}`,
      logicalSample && `${ref.sourceFileId}|logical:${logicalSample}`,
      ...(ref.sourceGroupKeys || []).map((key) => `${ref.sourceFileId}|source-group:${key}`),
    ].filter((key): key is string => Boolean(key));
    let group = matchKeys.map((key) => keyToGroup.get(key)).find(Boolean);
    if (!group) {
      group = { key: `${ref.sourceFileId}|${identity || logicalSample || fallback}`, label: identity || logicalSample || fallback, refs: [] };
      groups.push(group);
    }
    group.refs.push(ref);
    matchKeys.forEach((key) => keyToGroup.set(key, group!));
  });
  return groups;
}

function sourceLocation(ref: ExperimentSourceReference) {
  const coordinates = ref.sourceCoordinates || {};
  const directSheetName = firstString(coordinates, ['sheetName', 'sheet', 'worksheet']);
  const snapshotModel = asRecord(ref.recognitionSnapshot?.editModel);
  const contextSheets = Array.isArray(snapshotModel?.sourceContexts)
    ? snapshotModel.sourceContexts.map((context) => firstString(asRecord(context) || {}, ['sheetName']))
      .filter((value): value is string => Boolean(value))
    : [];
  const sheetName = [...new Set([directSheetName, ...contextSheets].filter((value): value is string => Boolean(value)))]
    .join('、');
  const cellRange = firstString(coordinates, ['cellRange', 'range', 'address', 'cell']);
  const rowNumber = coordinates.rowNumber ?? coordinates.row;
  const address = cellRange || (typeof rowNumber === 'number' ? `${rowNumber} 行` : undefined);
  const location = [sheetName || undefined, address || (sheetName ? '行号未提供' : undefined)]
    .filter((value): value is string => Boolean(value));
  return location.length ? location.join(' · ') : '来源行列位置未提供';
}

function sourceIdentity(ref: ExperimentSourceReference) {
  const value = ref.recognitionSnapshot?.sourceIdentity;
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined;
}

function firstString(values: Record<string, unknown>, keys: string[]) {
  for (const key of keys) {
    const value = values[key];
    if (typeof value === 'string' && value.trim()) return value.trim();
  }
  return undefined;
}
