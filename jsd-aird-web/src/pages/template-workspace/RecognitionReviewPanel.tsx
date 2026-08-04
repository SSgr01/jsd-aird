import {
  CheckCircleOutlined,
  CheckOutlined,
  EditOutlined,
  EnvironmentOutlined,
  EyeInvisibleOutlined,
  ReloadOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { Alert, Button, Empty, Popconfirm, Select } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';

import type {
  RecognitionReview,
  RecognitionReviewItem,
  RecognitionReviewStatus,
  TemplateQualityIssue,
} from '@/services/templates/template-api';

type ReviewFilter = 'ALL' | 'LOW' | 'QUALITY' | Exclude<RecognitionReviewStatus, 'IGNORED'>;

interface Props {
  review?: RecognitionReview;
  editable: boolean;
  selectedRecognitionItemId?: string;
  selectedQualityIssueId?: string;
  busy?: boolean;
  onSelect: (item: RecognitionReviewItem) => void;
  onConfirm: (item: RecognitionReviewItem) => void;
  onModify: (item: RecognitionReviewItem) => void;
  onIgnore: (item: RecognitionReviewItem) => void;
  onRestore: (item: RecognitionReviewItem) => void;
  onSelectQualityIssue: (issue: TemplateQualityIssue) => void;
  onApplyQualityIssue: (issue: TemplateQualityIssue) => void;
  onIgnoreQualityIssue: (issue: TemplateQualityIssue) => void;
  onRollbackQualityIssue: (issue: TemplateQualityIssue) => void;
}

const FILTERS: Array<{ value: ReviewFilter; label: string }> = [
  { value: 'ALL', label: '全部' },
  { value: 'PENDING', label: '待确认' },
  { value: 'LOW', label: '建议核对' },
  { value: 'CONFLICT', label: '需要处理' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'QUALITY', label: '规范建议' },
];

export function RecognitionReviewPanel({
  review,
  editable,
  selectedRecognitionItemId,
  selectedQualityIssueId,
  busy,
  onSelect,
  onConfirm,
  onModify,
  onIgnore,
  onRestore,
  onSelectQualityIssue,
  onApplyQualityIssue,
  onIgnoreQualityIssue,
  onRollbackQualityIssue,
}: Props) {
  const [filter, setFilter] = useState<ReviewFilter>('ALL');
  const [group, setGroup] = useState('ALL');
  const itemRefs = useRef(new Map<string, HTMLDivElement>());
  const qualityRefs = useRef(new Map<string, HTMLDivElement>());
  const visibleQualityIssues = useMemo(
    () => (review?.qualityIssues ?? []).filter((issue) => !isInternalRecoveryIssue(issue)),
    [review?.qualityIssues],
  );

  useEffect(() => {
    if (!selectedRecognitionItemId) return;
    itemRefs.current.get(selectedRecognitionItemId)?.scrollIntoView?.({ block: 'nearest' });
  }, [selectedRecognitionItemId]);

  useEffect(() => {
    if (!selectedQualityIssueId) return;
    qualityRefs.current.get(selectedQualityIssueId)?.scrollIntoView?.({ block: 'nearest' });
  }, [selectedQualityIssueId]);

  const items = useMemo(() => (review?.items ?? []).filter((item) => {
    const matchesStatus = filter !== 'QUALITY' && (filter === 'ALL'
      || filter === 'LOW' && item.confidence < 0.65 && item.status !== 'IGNORED'
      || item.status === filter);
    return matchesStatus && (group === 'ALL' || item.groupName === group);
  }), [filter, group, review?.items]);

  if (!review?.recognitionRunId) {
    return (
      <section className="recognition-review-pane" role="tabpanel">
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="当前模板还没有识别结果，可在上方重新识别整份工作簿"
        />
      </section>
    );
  }

  if (review.runStatus === 'FAILED' && !review.items.length && !review.qualityIssues.length) {
    return (
      <section className="recognition-review-pane" role="tabpanel" aria-label="识别确认">
        <Alert
          type="warning"
          showIcon
          message="智能识别未完成"
          description="工作簿内容和原有字段已保留，可在上方重新识别整份工作簿。"
        />
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="本次没有生成可确认的识别结果"
        />
      </section>
    );
  }

  const filterCounts: Record<ReviewFilter, number> = {
    ALL: review.items.length,
    PENDING: review.summary.pending,
    LOW: review.summary.lowConfidence,
    CONFLICT: review.summary.conflict,
    CONFIRMED: review.summary.confirmed,
    QUALITY: visibleQualityIssues.length,
  };

  return (
    <section className="recognition-review-pane" role="tabpanel" aria-label="识别确认">
      {(review.runStatus === 'FAILED' || review.runStatus === 'PARTIAL') && (
        <Alert
          type="warning"
          showIcon
          message="智能识别未完成"
          description="本次识别部分内容未完成，已识别字段仍可确认，也可以重新识别或手工补充字段。"
        />
      )}
      <div className="recognition-filter-toolbar">
        <div className="recognition-status-filter" aria-label="识别状态筛选">
          {FILTERS.map((item) => (
            <button
              type="button"
              key={item.value}
              aria-pressed={filter === item.value}
              onClick={() => setFilter(item.value)}
            >
              <span>{item.label}</span>
              <span className="recognition-filter-count">{filterCounts[item.value]}</span>
            </button>
          ))}
        </div>
        <Select
          className="recognition-group-filter"
          aria-label="字段分组筛选"
          value={group}
          onChange={setGroup}
          options={[
            { value: 'ALL', label: '全部分组' },
            ...review.groups.map((name) => ({ value: name, label: name })),
          ]}
        />
      </div>

      <div className="recognition-review-list" aria-busy={busy}>
        {(filter === 'ALL' || filter === 'QUALITY') && visibleQualityIssues.map((issue) => {
          const selected = issue.id === selectedQualityIssueId;
          const preview = qualityPreview(issue);
          return (
            <div
              key={issue.id}
              ref={(node) => {
                if (node) qualityRefs.current.set(issue.id, node);
                else qualityRefs.current.delete(issue.id);
              }}
              className="recognition-review-item quality-review-item"
              data-status={issue.severity === 'BLOCKER' ? 'conflict' : 'pending'}
              aria-current={selected}
            >
              <button
                type="button"
                className="recognition-review-row"
                aria-expanded={selected}
                onClick={() => onSelectQualityIssue(issue)}
              >
                <StatusIndicator status={issue.severity === 'BLOCKER' ? 'CONFLICT' : 'PENDING'} />
                <span className="recognition-row-content">
                  <strong>{issue.title}</strong>
                  <span>{issue.status === 'AUTO_APPLIED' ? '已自动规范' : '模板规范'}</span>
                </span>
                <span className="recognition-row-location">
                  <EnvironmentOutlined aria-hidden="true" /> {issue.sheetName} · {issue.address}
                </span>
              </button>
              {selected && (
                <div className="recognition-review-expanded">
                  <p className={issue.severity === 'BLOCKER' ? 'recognition-conflict-reason' : undefined}>
                    {issue.description}
                  </p>
                  {issue.businessImpact && <p>{issue.businessImpact}</p>}
                  {preview && (
                    <dl className="recognition-quality-preview">
                      <div><dt>修正前</dt><dd>{preview.before}</dd></div>
                      <div><dt>修正后</dt><dd>{preview.after}</dd></div>
                    </dl>
                  )}
                  <div className="recognition-review-actions">
                    {issue.status === 'AUTO_APPLIED' || issue.status === 'CONFIRMED' ? (
                      <Button
                        size="small"
                        icon={<ReloadOutlined />}
                        disabled={!editable || busy || !Object.keys(issue.inversePatch).length}
                        onClick={() => onRollbackQualityIssue(issue)}
                      >
                        撤销自动规范
                      </Button>
                    ) : (
                      <>
                        {Object.keys(issue.suggestedPatch).length > 0 && (
                          <Button
                            size="small"
                            type="primary"
                            disabled={!editable || busy}
                            onClick={() => onApplyQualityIssue(issue)}
                          >
                            应用建议
                          </Button>
                        )}
                        <Button
                          size="small"
                          type="text"
                          disabled={!editable || busy}
                          onClick={() => onIgnoreQualityIssue(issue)}
                        >
                          忽略
                        </Button>
                      </>
                    )}
                  </div>
                </div>
              )}
            </div>
          );
        })}
        {items.map((item) => {
          const selected = item.id === selectedRecognitionItemId;
          return (
            <div
              key={item.id}
              ref={(node) => {
                if (node) itemRefs.current.set(item.id, node);
                else itemRefs.current.delete(item.id);
              }}
              className="recognition-review-item"
              data-status={item.status.toLowerCase()}
              aria-current={selected}
            >
          <button
            type="button"
            className="recognition-review-row"
            aria-expanded={selected}
            onClick={() => onSelect(item)}
          >
            <StatusIndicator status={item.status} />
            <span className="recognition-row-content">
              <strong>{item.fieldName}</strong>
              <span>{kindLabel(item.kind)}</span>
            </span>
            <span className="recognition-row-location">
              <EnvironmentOutlined aria-hidden="true" /> {locationText(item)}
            </span>
            {item.status !== 'CONFIRMED' && (
              <span className="recognition-row-status">{statusLabel(item.status)}</span>
            )}
          </button>
              {selected && (
                <div className="recognition-review-expanded">
                  <p className={item.status === 'CONFLICT' ? 'recognition-conflict-reason' : undefined}>
                    {item.status === 'CONFLICT' && <WarningOutlined />}
                    {item.conflictReason || item.description || '根据模板内容自动识别'}
                  </p>
                  <dl className="recognition-review-details">
                    <div><dt>所属分组</dt><dd>{item.groupName}</dd></div>
                    <div><dt>处理建议</dt><dd>{confidenceLabel(item.confidenceLevel)}</dd></div>
                  </dl>
                  <div className="recognition-review-actions">
                    {item.status === 'IGNORED' ? (
                      <Button
                        size="small"
                        icon={<ReloadOutlined />}
                        aria-label="恢复"
                        disabled={!editable || busy}
                        onClick={() => onRestore(item)}
                      >
                        恢复
                      </Button>
                    ) : (
                      <>
                        {item.status !== 'CONFIRMED' && (
                          <Button
                            size="small"
                            type="primary"
                            icon={<CheckOutlined />}
                            aria-label="确认"
                            disabled={!editable || busy}
                            onClick={() => onConfirm(item)}
                          >
                            确认
                          </Button>
                        )}
                        <Button
                          size="small"
                          icon={<EditOutlined />}
                          aria-label="修改"
                          disabled={!editable || busy}
                          onClick={() => onModify(item)}
                        >
                          修改
                        </Button>
                        <Popconfirm
                          title={`忽略“${item.fieldName}”？`}
                          description="只移除字段定义，不会删除 Excel 中的原内容。"
                          okText="忽略"
                          cancelText="取消"
                          onConfirm={() => onIgnore(item)}
                        >
                          <Button
                            size="small"
                            danger
                            type="text"
                            icon={<EyeInvisibleOutlined />}
                            aria-label="忽略"
                            disabled={!editable || busy}
                          >
                            忽略
                          </Button>
                        </Popconfirm>
                      </>
                    )}
                  </div>
                </div>
              )}
            </div>
          );
        })}
        {!items.length && !(filter === 'ALL' || filter === 'QUALITY') && (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有符合条件的识别项目" />
        )}
        {filter === 'QUALITY' && !visibleQualityIssues.length && (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有发现模板规范问题" />
        )}
      </div>
    </section>
  );
}

function StatusIndicator({ status }: { status: RecognitionReviewStatus }) {
  return (
    <span className="recognition-status-indicator" data-status={status.toLowerCase()} aria-label={statusLabel(status)}>
      {status === 'CONFIRMED' ? <CheckCircleOutlined />
        : status === 'CONFLICT' ? <WarningOutlined />
          : <span className="recognition-status-dot" />}
    </span>
  );
}

function statusLabel(status: RecognitionReviewStatus) {
  if (status === 'CONFIRMED') return '已确认';
  if (status === 'CONFLICT') return '需要处理';
  if (status === 'IGNORED') return '已忽略';
  return '待确认';
}

function kindLabel(kind: RecognitionReviewItem['kind']) {
  if (kind === 'ROW_TABLE') return '明细表';
  if (kind === 'MATRIX') return '矩阵表';
  return '普通字段';
}

function confidenceLabel(level: RecognitionReviewItem['confidenceLevel']) {
  if (level === 'HIGH') return '可直接确认';
  if (level === 'MEDIUM') return '建议看一眼位置和名称';
  return '建议核对后再使用';
}

function locationText(item: RecognitionReviewItem) {
  const address = item.address || item.labelAddress || '未定位';
  return `${item.sheetName || '当前工作表'} · ${address}`;
}

function qualityPreview(issue: TemplateQualityIssue) {
  const operations: unknown = issue.suggestedPatch.operations;
  const changes = Array.isArray(operations) ? operations.filter(isCellPatchOperation) : [];
  if (!changes.length) return undefined;
  const display = (value: unknown) => {
    const text = typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'
      ? String(value).trim() : '';
    return text || '空白';
  };
  const before = changes.map((operation) => display(operation.expectedValue)).join(' ｜ ');
  const after = changes.map((operation) => display(operation.value)).join(' ｜ ');
  return { before: before || '空白', after: after || '空白' };
}

function isInternalRecoveryIssue(issue: TemplateQualityIssue) {
  return new Set([
    '部分字段关系需要核对',
    '部分表格结构需要核对',
    '部分业务区域需要核对',
  ]).has(issue.title)
    || issue.evidence.some((item) => item.internalRecovery === true);
}

function isCellPatchOperation(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
    && (value as Record<string, unknown>).op === 'SET_CELL';
}
