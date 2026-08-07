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
  LongTableModel,
  MatrixModel,
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
  onConfirm: (item: RecognitionReviewItem, selectedSuggestionId?: string) => void;
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
  const [selectedAlternatives, setSelectedAlternatives] = useState<Record<string, string>>({});
  const itemRefs = useRef(new Map<string, HTMLDivElement>());
  const qualityRefs = useRef(new Map<string, HTMLDivElement>());
  const visibleQualityIssues = useMemo(
    () => (review?.qualityIssues ?? []).filter((issue) => !isInternalRecoveryIssue(issue)),
    [review?.qualityIssues],
  );
  const structureBlocks = useMemo(
    () => review?.semanticModel?.businessBlocks ?? [],
    [review?.semanticModel?.businessBlocks],
  );

  useEffect(() => {
    if (!selectedRecognitionItemId) return;
    itemRefs.current.get(selectedRecognitionItemId)?.scrollIntoView?.({ block: 'nearest' });
  }, [selectedRecognitionItemId]);

  useEffect(() => {
    if (!selectedQualityIssueId) return;
    qualityRefs.current.get(selectedQualityIssueId)?.scrollIntoView?.({ block: 'nearest' });
  }, [selectedQualityIssueId]);

  const items = useMemo(
    () =>
      (review?.items ?? []).filter((item) => {
        const matchesStatus =
          filter !== 'QUALITY' &&
          (filter === 'ALL' ||
            (filter === 'LOW' && item.confidence < 0.65 && item.status !== 'IGNORED') ||
            item.status === filter);
        return matchesStatus && (group === 'ALL' || item.groupName === group);
      }),
    [filter, group, review?.items],
  );

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
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="本次没有生成可确认的识别结果" />
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
      {(review.runStatus === 'FAILED' || review.runStatus === 'PARTIAL' || review.runStatus === 'TRUNCATED') && (
        <Alert
          type="warning"
          showIcon
          message={review.runStatus === 'TRUNCATED' ? '模型输出过长，识别未完成' : '智能识别未完成'}
          description={review.runStatus === 'PARTIAL'
            ? '本次识别部分内容未完成，已识别字段仍可确认，也可以重新识别或手工补充字段。'
            : '以下“物理结构”或“规则识别”内容是待核对候选，不代表模型已经确认；未确认前不会进入正式模板。'}
        />
      )}
      {review.recognitionStatus === 'REVIEW_REQUIRED' && (
        <Alert
          type="warning"
          showIcon
          message="物理结构已识别，但语义覆盖仍需确认"
          description={coverageDescription(review.recognitionCoverage)}
        />
      )}
      {(review.semanticModel?.diagnostics?.length ?? 0) > 0 && (
        <Alert
          type="warning"
          showIcon
          message={`有 ${review.semanticModel?.diagnostics?.length ?? 0} 项识别结构需要处理`}
          description="被协议拒绝的原始候选仅保留在诊断中，不会进入确认列表、字段 Mapping 或正式模板。"
        />
      )}
      {structureBlocks.length > 0 && (
        <section className="recognition-structure-summary" aria-label="识别到的业务区域">
          <div className="recognition-structure-heading">
            <div>
              <strong>先识别出的区域结构</strong>
              <span>区域结构用于定位，只有下方字段确认后才会进入正式模板。</span>
            </div>
            <span>{structureBlocks.length} 个区域</span>
          </div>
          <div className="recognition-structure-list">
            {structureBlocks.map((block) => (
              <div className="recognition-structure-card" key={block.blockId}>
                <span className="recognition-structure-type">{blockTypeLabel(block.type)}</span>
                <strong>{block.businessName || '未命名区域'}</strong>
                <span>{block.range}</span>
              </div>
            ))}
          </div>
        </section>
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
        {(filter === 'ALL' || filter === 'QUALITY') &&
          visibleQualityIssues.map((issue) => {
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
                    <p
                      className={
                        issue.severity === 'BLOCKER' ? 'recognition-conflict-reason' : undefined
                      }
                    >
                      {issue.description}
                    </p>
                    {issue.businessImpact && <p>{issue.businessImpact}</p>}
                    {preview && (
                      <dl className="recognition-quality-preview">
                        <div>
                          <dt>修正前</dt>
                          <dd>{preview.before}</dd>
                        </div>
                        <div>
                          <dt>修正后</dt>
                          <dd>{preview.after}</dd>
                        </div>
                      </dl>
                    )}
                    <div className="recognition-review-actions">
                      {issue.status === 'AUTO_APPLIED' || issue.status === 'CONFIRMED' ? (
                        <Button
                          size="small"
                          icon={<ReloadOutlined />}
                          disabled={!editable || busy || !Object.keys(issue.inversePatch ?? {}).length}
                          onClick={() => onRollbackQualityIssue(issue)}
                        >
                          撤销自动规范
                        </Button>
                      ) : (
                        <>
                          {Object.keys(issue.suggestedPatch ?? {}).length > 0 && (
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
          const alternatives = item.payload.structureAlternatives ?? [];
          const selectedAlternativeId = selectedAlternatives[item.id];
          const runtimeSlots = item.payload.columnSlots ?? [];
          const runtimeSlotCoordinates = runtimeSlots
            .map((slot) => slot.column || slot.identityAddress)
            .filter(Boolean);
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
                  <span>
                    {item.payload.suggestionLevel === 'CHILD' ? '明细字段' : kindLabel(item.kind)} · {sourceLabel(item.source)}
                  </span>
                </span>
                <span className="recognition-row-location">
                  <EnvironmentOutlined aria-hidden="true" /> {locationText(item)}
                </span>
                {item.status !== 'CONFIRMED' && (
                  <span className="recognition-row-status">
                    {item.payload.requiresStandardConfirmation
                      ? '待选择业务字段'
                      : item.payload.runtimeInputOnly
                        ? '结构已识别，列名填写时补充'
                      : item.payload.physicalStructureOnly
                        ? '物理结构候选，待确认'
                      : item.payload.protocolRecovery
                        ? '协议异常候选，待复核'
                      : item.payload.candidateOnly
                         ? '识别候选，待确认'
                      : statusLabel(item.status)}
                  </span>
                )}
              </button>
              {selected && (
                <div className="recognition-review-expanded">
                  <p
                    className={
                      item.status === 'CONFLICT' ? 'recognition-conflict-reason' : undefined
                    }
                  >
                    {item.source === 'RULE' && <span className="recognition-source-badge">规则识别</span>}
                    {item.source === 'PHYSICAL' && <span className="recognition-source-badge">物理结构</span>}
                    {item.status === 'CONFLICT' && <WarningOutlined />}
                    {item.conflictReason ||
                      item.payload.conflictMessage ||
                      item.description ||
                      '根据模板内容自动识别'}
                  </p>
                  {item.reasonDetail && <p className="recognition-reason-detail">{item.reasonDetail}</p>}
                  {item.payload.candidateOnly && (
                    <div className="field-property-alert full recognition-candidate-alert" role="status">
                      <strong>这是待确认候选，不是正式字段</strong>
                      <span>
                        {item.payload.physicalStructureOnly
                          ? '物理结构候选，尚未完成语义裁决'
                          : pendingReasonLabel(item.payload.pendingReason, item.payload.protocolRecovery)}。请确认后再保存；未确认前不会编译进正式模板。
                      </span>
                    </div>
                  )}
                  {item.payload.runtimeInputOnly && !item.payload.candidateOnly && (
                    <div className="field-property-alert full recognition-runtime-input-alert" role="status">
                      <strong>这是运行时填写位置</strong>
                      <span>
                        {runtimeSlotCoordinates.length > 0
                          ? `${runtimeSlotCoordinates.join('、')} 是模板预留的真实列槽位`
                          : '这些位置是模板预留的真实列槽位'}，不是多个业务字段。实验员填写列名后，系统会按列生成对应记录；空白槽位不会阻止模板保存或发布。
                      </span>
                    </div>
                  )}
                  {item.payload.requiresStandardConfirmation && (
                    <div className="field-property-alert full" role="status">
                      <strong>标准字段待确认</strong>
                      <span>
                        当前名称未匹配现有标准字典。请点击“修改”，选择已有业务字段，或转为模板自定义字段；处理前不会进入正式模板。
                      </span>
                    </div>
                  )}
                  {alternatives.length > 1 && (
                    <div className="field-property-alert full recognition-structure-alternatives" role="group" aria-label="结构候选选择">
                      <strong>系统发现两种结构判断</strong>
                      <span>请选择要采用的结构，确认后其他候选会被自动拒绝。</span>
                      <Select
                        aria-label="选择结构候选"
                        placeholder="请选择结构"
                        value={selectedAlternativeId}
                        onChange={(value) =>
                          setSelectedAlternatives((current) => ({ ...current, [item.id]: value }))
                        }
                        options={alternatives.map((alternative) => ({
                          value: alternative.suggestionId,
                          label: `${alternative.source === 'PHYSICAL' ? '物理结构' : '模型判断'}：${alternative.kind || '结构'} ${alternative.range || '坐标待确认'}`,
                        }))}
                      />
                    </div>
                  )}
                  <dl className="recognition-review-details">
                    <div>
                      <dt>所属分组</dt>
                      <dd>{item.groupName}</dd>
                    </div>
                    <div>
                      <dt>处理建议</dt>
                      <dd>{confidenceLabel(item.confidenceLevel)}</dd>
                    </div>
                  </dl>
                    {item.kind === 'MATRIX' && item.payload.matrixModel ? (
                      <>
                        <MatrixStructureSummary
                          model={item.payload.matrixModel}
                          title={item.fieldName || '交叉表结构'}
                        />
                        {item.payload.longTableModel && (
                          <MatrixLongTablePreview model={item.payload.longTableModel} />
                        )}
                      </>
                    ) : item.payload.longTableModel ? (
                      <MatrixLongTablePreview model={item.payload.longTableModel} />
                    ) : item.kind === 'ROW_TABLE' && Array.isArray(item.payload.columns) && item.payload.columns.length ? (
                    <div className="recognition-table-columns">
                      <strong>明细字段（可单独确认和同步）</strong>
                      {item.payload.columns.some((column) => typeof column?.name === 'string' && column.name.trim()) ? (
                        <div className="recognition-table-column-list">
                          {item.payload.columns
                            .filter((column) => typeof column?.name === 'string' && column.name.trim())
                            .map((column) => (
                              <span key={column.relationId || column.bindingId || column.code}>
                                {column.name} · {column.valueType || 'string'}
                                {column.unit ? ` · ${column.unit}` : ''}
                                {column.valueRange ? ` · ${column.valueRange}` : ''}
                              </span>
                            ))}
                        </div>
                      ) : (
                        <small>原表未提供表头，系统已按坐标关系保留明细字段。</small>
                      )}
                      {textValue(
                        item.payload.tableModel?.totalRange || item.payload.locator?.totalRange,
                      ) && (
                        <small>
                          合计/小计范围：
                          {textValue(
                            item.payload.tableModel?.totalRange || item.payload.locator?.totalRange,
                          )}
                        </small>
                      )}
                      <small>{layoutDescription(item.payload)}</small>
                    </div>
                  ) : null}
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
                            disabled={
                              !editable ||
                              busy ||
                              Boolean(item.payload.semanticConflict) ||
                              Boolean(item.payload.requiresStandardConfirmation) ||
                              (Boolean(item.payload.candidateOnly) && alternatives.length <= 1) ||
                              (Boolean(item.payload.reviewRequired) && alternatives.length <= 1) ||
                              (Boolean(item.payload.physicalStructureOnly) && alternatives.length <= 1) ||
                              (Boolean(item.payload.structureConflict) && !selectedAlternativeId)
                            }
                            onClick={() => onConfirm(item, selectedAlternativeId)}
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

function MatrixLongTablePreview({ model }: { model: LongTableModel }) {
  const slots = model.recordProjection?.recordAxis === 'ROW'
    ? (model.rowSlots ?? [])
    : (model.columnSlots ?? []);
  const runtimeSlots = slots.filter((slot) => slot.templateStatus === 'RUNTIME_INPUT').length;
  const emptyRuntimeSlots = slots.filter((slot) => slot.instanceStatus === 'EMPTY').length;
  const pendingAxes = model.records.filter((record) => {
    const member = model.recordProjection?.recordAxis === 'ROW'
      ? record.rowMember
      : record.columnMember;
    return member?.status === 'PENDING';
  }).length;
  const roles = Array.from(new Set(model.records.map((record) => record.rowRole)));
  const preview = model.records.slice(0, 8);
  return (
    <div className="recognition-matrix-preview">
      <div className="recognition-matrix-preview-heading">
        <strong>长表结构预览</strong>
        <span>
          {model.sourceKind === 'MATRIX' ? '矩阵' : '表格'} ·
          {runtimeSlots ? ' ' + String(runtimeSlots) + ' 个运行时列槽位' : ' 列槽位已识别'} ·
          {emptyRuntimeSlots ? ' ' + String(emptyRuntimeSlots) + ' 个名称待填写' : ' 列名称已填写'}
           {pendingAxes ? ' · ' + String(pendingAxes) + ' 条待确认记录' : ''}
        </span>
      </div>
      <small>
         行标题按层级保留，重复文本不会合并，重复记录和汇总行分别保留。
         当前行类型：{roles.map(rowRoleLabel).join('、') || '待识别'}。
         可用于训练：{model.trainingSummary?.eligible ?? model.records.filter((record) => record.trainingEligible).length} 条。
      </small>
      <div className="recognition-matrix-preview-table" role="table" aria-label="矩阵长表预览">
        <div className="recognition-matrix-preview-row header" role="row">
          <span>行路径</span><span>列坐标</span><span>行类型</span><span>值</span>
        </div>
        {preview.map((record) => (
          <div className="recognition-matrix-preview-row" role="row" key={record.recordKey}>
            <span title={record.rowPath.join(' / ')}>{record.rowPath.filter(Boolean).join(' / ') || '未命名行'}</span>
            <span>
              {model.recordProjection?.recordAxis === 'ROW'
                ? `${record.rowMember?.label || '行成员待确认'} · ${record.rowMember?.address || '待确认'}`
                : `${record.columnMember?.coordinate || '列成员待确认'} · ${record.columnMember?.label || '标题待确认'}`}
            </span>
            <span>{rowRoleLabel(record.rowRole)}</span>
            <span>{displayLongTableValue(record.value.value ?? record.value.formula)}</span>
          </div>
        ))}
      </div>
      {model.records.length > preview.length && <small>仅展示前 {preview.length} 条，完整坐标记录随模板保存。</small>}
    </div>
  );
}

function MatrixStructureSummary({ model, title }: { model: MatrixModel; title: string }) {
  const isRowProjection = model.recordAxis === 'ROW';
  const slots = isRowProjection ? (model.rowSlots ?? []) : (model.columnSlots ?? []);
  const incomplete = !model.rowHeaderRange || !model.columnHeaderRange || !model.crossDataRange;
  const rowAxisNames = [...(model.rowDimensions ?? []), ...(model.rowAttributes ?? [])]
    .map((axis) => axis.name)
    .filter(Boolean);
  return (
    <section className="recognition-matrix-structure" aria-label={`${title}结构`}>
      <div className="recognition-matrix-structure-heading">
        <strong>{title}</strong>
        <span>
          类型：{model.semanticMode === 'CROSS_TAB' ? '交叉测试表' : '待确认'} · 记录方向：
          {model.recordAxis === 'COLUMN' ? '按列' : model.recordAxis === 'ROW' ? '按行' : '未确定'}
        </span>
      </div>
      {incomplete && (
        <div className="field-property-alert full" role="status">
          结构信息不完整，请重新识别或人工确认
        </div>
      )}
      <dl>
        <div>
            <dt>{isRowProjection ? '行成员名称填写位置' : '列成员名称填写位置'}</dt>
            <dd>{(isRowProjection ? model.rowHeaderRange : model.columnHeaderRange) || '待确认'}</dd>
        </div>
        <div>
          <dt>行维度及属性</dt>
          <dd>
            <span>{rowAxisNames.join('、') || '行维度'}</span>
            <span>{model.rowHeaderRange || '待确认'}</span>
          </dd>
        </div>
        <div>
          <dt>交叉值填写区域</dt>
          <dd>{model.crossDataRange || '待确认'}</dd>
        </div>
        <div>
            <dt>{isRowProjection ? '可用行槽位' : '可用列槽位'}</dt>
          <dd>{slots.length || 0} 个</dd>
        </div>
      </dl>
    </section>
  );
}

function rowRoleLabel(role: LongTableModel['records'][number]['rowRole']) {
  switch (role) {
    case 'TEST_ITEM':
      return '测试项目';
    case 'REPLICATE':
      return '重复记录';
    case 'AGGREGATE':
      return '自动计算结果';
    default:
      return '待确认';
  }
}

function displayLongTableValue(value: unknown) {
  if (value === null || value === undefined || value === '') return '空白输入';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return `${value}`;
  return JSON.stringify(value) ?? '复杂值';
}

function StatusIndicator({ status }: { status: RecognitionReviewStatus }) {
  return (
    <span
      className="recognition-status-indicator"
      data-status={status.toLowerCase()}
      aria-label={statusLabel(status)}
    >
      {status === 'CONFIRMED' ? (
        <CheckCircleOutlined />
      ) : status === 'CONFLICT' ? (
        <WarningOutlined />
      ) : (
        <span className="recognition-status-dot" />
      )}
    </span>
  );
}

function sourceLabel(source?: RecognitionReviewItem['source']) {
  return source === 'RULE' ? '规则识别' : source === 'MODEL' ? '模型识别' : source === 'PHYSICAL' ? '物理结构' : '人工补充';
}

function coverageDescription(coverage?: RecognitionReview['recognitionCoverage']) {
  if (!coverage) return '识别结果不是完整业务语义，确认前不会作为正式字段结构使用。';
  const expected = coverage.expectedRegionCount ?? coverage.physicalRegionCount ?? 0;
  const covered = coverage.coveredRegionCount ?? 0;
  const unresolved = coverage.unresolvedRegionCount ?? Math.max(0, expected - covered);
  return `已覆盖 ${covered}/${expected} 个物理区域，仍有 ${unresolved} 个区域待确认；当前候选不会被视为完整识别结果。`;
}

function statusLabel(status: RecognitionReviewStatus) {
  if (status === 'CONFIRMED') return '已确认';
  if (status === 'CONFLICT') return '需要确认含义';
  if (status === 'IGNORED') return '已忽略';
  return '待确认';
}

function kindLabel(kind: RecognitionReviewItem['kind']) {
  if (kind === 'ROW_TABLE') return '明细表';
  if (kind === 'COLUMN_TABLE') return '横向明细表';
  if (kind === 'MATRIX') return '矩阵表';
  if (kind === 'FREE_TEXT') return '自由文本区';
  return '普通字段';
}

function blockTypeLabel(type: string) {
  if (type === 'FORM_REGION' || type === 'FORM_FIELDS') return '字段区';
  if (type === 'ROW_TABLE') return '按行明细表';
  if (type === 'COLUMN_TABLE') return '按列明细表';
  if (type === 'MATRIX') return '矩阵区';
  if (type === 'FREE_TEXT') return '说明文本区';
  if (type === 'STATIC_REFERENCE') return '固定内容区';
  if (type === 'DOCUMENT_HEADER') return '文档标题区';
  if (type === 'INSTRUCTION_LIST') return '填写说明区';
  if (type === 'NOTE_BLOCK') return '备注区';
  return '待确认结构';
}

function pendingReasonLabel(reason?: string, recovery?: string) {
  if (reason === 'RUNTIME_INPUT') return '这是模板预留的运行时填写位置，不是识别失败';
  if (reason === 'PROTOCOL_REVIEW_REQUIRED') return '原始识别结果与协议不一致，系统已保留候选供人工复核';
  if (reason === 'TABLE_STRUCTURE_UNCLEAR') return '表格结构仍需核对';
  if (reason === 'MATRIX_AXIS_LABEL_PENDING') return '矩阵列标题为空，系统保留 C、D 等真实坐标，等待用户补充或确认列含义';
  if (recovery === 'RETAINED_REJECTED_CANDIDATE') return '该结果是协议校验后保留的原始候选';
  return '系统尚未确认该识别结果';
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

function layoutDescription(payload: RecognitionReviewItem['payload']) {
  const axis = payload.repeatAxis === 'COLUMN' ? '按列填写' : '按行填写';
  const height = payload.recordHeight || 1;
  const width = payload.recordWidth || 1;
  const termination = payload.terminationRule?.type;
  const terminationLabel =
    termination === 'UNTIL_TOTAL_ROW'
      ? '遇到合计行停止'
      : termination === 'UNTIL_LABEL'
        ? `遇到“${displayScalar(payload.terminationRule?.label)}”停止`
        : termination === 'FIXED_COUNT'
          ? `最多 ${displayScalar(payload.terminationRule?.maxRecords)} 条`
          : termination === 'UNTIL_EMPTY_RECORD'
            ? '遇到空记录停止'
            : '到区域边界停止';
  return `填写方向：${axis}；每条记录 ${height} 行 × ${width} 列；${terminationLabel}`;
}

function textValue(value: unknown) {
  return typeof value === 'string' && value.trim() ? value : '';
}

function displayScalar(value: unknown) {
  return typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'
    ? String(value)
    : '';
}

function qualityPreview(issue: TemplateQualityIssue) {
  const operations: unknown = issue.suggestedPatch?.operations;
  const changes = Array.isArray(operations) ? operations.filter(isCellPatchOperation) : [];
  if (!changes.length) return undefined;
  const display = (value: unknown) => {
    const text =
      typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'
        ? String(value).trim()
        : '';
    return text || '空白';
  };
  const before = changes.map((operation) => display(operation.expectedValue)).join(' ｜ ');
  const after = changes.map((operation) => display(operation.value)).join(' ｜ ');
  return { before: before || '空白', after: after || '空白' };
}

function isInternalRecoveryIssue(issue: TemplateQualityIssue) {
  return (
    new Set(['部分字段关系需要核对', '部分表格结构需要核对', '部分业务区域需要核对']).has(
      issue.title,
    ) || (issue.evidence ?? []).some((item) => item.internalRecovery === true)
  );
}

function isCellPatchOperation(value: unknown): value is Record<string, unknown> {
  return (
    Boolean(value) &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    (value as Record<string, unknown>).op === 'SET_CELL'
  );
}
