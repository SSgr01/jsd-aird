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
  RecognitionRegionNode,
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
  onConfirm: (item: RecognitionReviewItem, selectedAlternativeId?: string) => void;
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
    () => (review?.qualityIssues ?? [])
      .filter(isActionableTemplateIssue)
      .filter((issue) => issue.status !== 'IGNORED'),
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
  const canonicalRegions = useMemo(
    () => deduplicateRegions(review?.regions ?? []),
    [review?.regions],
  );
  const visibleRegions = useMemo(
    () => filterRegionTree(canonicalRegions, filter, group),
    [canonicalRegions, filter, group],
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
    ALL: review.regions?.length
      ? canonicalRegions.flatMap((region) => region.fields ?? []).length
      : review.items.length,
    PENDING: review.regions?.length
      ? review.statistics?.pendingFieldCount ?? 0
      : review.summary.pending,
    LOW: review.summary.lowConfidence,
    CONFLICT: review.summary.conflict,
    CONFIRMED: review.summary.confirmed,
    QUALITY: visibleQualityIssues.length,
  };
  if (review.regions?.length) {
    const fields = canonicalRegions.flatMap((region) => region.fields ?? []);
    filterCounts.CONFIRMED = fields.filter((field) => field.status === 'CONFIRMED').length;
    filterCounts.CONFLICT = review.statistics?.structureConflictGroups ?? 0;
    filterCounts.LOW = fields.filter((field) => field.status !== 'IGNORED' && field.confidence < 0.65).length;
  }
  const visibleFilters = FILTERS.filter((item) => item.value === 'ALL' || filterCounts[item.value] > 0);
  const coverageComplete = isCoverageComplete(review.recognitionCoverage);
  const hasPendingRecognitionWork = filterCounts.PENDING > 0
    || filterCounts.CONFLICT > 0
    || !coverageComplete
    || ((review.runStatus === 'FAILED' || review.runStatus === 'PARTIAL' || review.runStatus === 'TRUNCATED')
      && !coverageComplete);

  return (
    <section className="recognition-review-pane" role="tabpanel" aria-label="识别确认">
      {hasPendingRecognitionWork && (
        <Alert
          type="warning"
          showIcon
          message="还有字段需要确认"
          description={(
            <div className="recognition-summary-lines">
              <div>{coverageComplete
                ? `已识别 ${review.statistics?.fieldCount ?? filterCounts.ALL} 个字段，请核对名称和填写位置。`
                : coverageDescription(review.recognitionCoverage)}</div>
              {(review.runStatus === 'FAILED' || review.runStatus === 'PARTIAL' || review.runStatus === 'TRUNCATED') && !coverageComplete && (
                <div>部分内容未完成，可重新识别或手工补充。</div>
              )}
            </div>
          )}
        />
      )}
      <div className="recognition-filter-toolbar">
        <div className="recognition-status-filter" aria-label="识别状态筛选">
          {visibleFilters.map((item) => (
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
        {!review.regions?.length && review.groups.length > 1 && (
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
        )}
      </div>

      <div className="recognition-review-list" aria-busy={busy}>
        {(filter === 'ALL' || filter === 'QUALITY') && visibleQualityIssues.length > 0 && (
          <div className="recognition-section-heading"><strong>规范问题</strong><span>{visibleQualityIssues.length} 项</span></div>
        )}
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
        {filter !== 'QUALITY' && review.regions?.length ? (
          <div className="recognition-section-heading"><strong>区域与字段</strong><span>{canonicalRegions.length} 个区域</span></div>
        ) : null}
        {filter !== 'QUALITY' && (review.regions?.length ? (
          <RecognitionRegionTree
            regions={visibleRegions}
            review={review}
            editable={editable}
            busy={busy}
            selectedRecognitionItemId={selectedRecognitionItemId}
            selectedAlternatives={selectedAlternatives}
            onSelectAlternative={(regionId, alternativeId) =>
              setSelectedAlternatives((current) => ({ ...current, [regionId]: alternativeId }))
            }
            onSelect={onSelect}
            onConfirm={onConfirm}
            onModify={onModify}
            onIgnore={onIgnore}
            onRestore={onRestore}
          />
        ) : items.map((item) => {
          const selected = item.id === selectedRecognitionItemId;
          const alternatives = item.payload.structureAlternatives ?? [];
          const selectedAlternativeId = selectedAlternatives[item.id];
          const structuralCandidate = isStructuralKind(item.kind);
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
                    {item.payload.suggestionLevel === 'CHILD' ? '明细字段' : kindLabel(item.kind)}
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
                  {item.payload.nameSource && item.payload.nameSource !== 'MODEL' && (
                    <div className="field-property-alert full" role="status">
                      <strong>字段名称来自物理回退</strong>
                      <span>名称来源：{nameSourceLabel(item.payload.nameSource)}，请确认后再发布。</span>
                    </div>
                  )}
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
                    <div
                      className="field-property-alert full recognition-structure-alternatives"
                      role="group"
                      aria-label="结构方案选择"
                    >
                      <strong>系统发现 {alternatives.length} 种结构方案</strong>
                      <span id={`structure-alternative-help-${item.id}`}>
                        一个方案可以包含多个互补区域；确认后其他方案会被自动拒绝。
                      </span>
                      <Select
                        aria-label="选择结构方案"
                        aria-describedby={`structure-alternative-help-${item.id}`}
                        placeholder="请选择结构方案"
                        value={selectedAlternativeId}
                        onChange={(value) =>
                          setSelectedAlternatives((current) => ({ ...current, [item.id]: value }))
                        }
                        options={alternatives.map((alternative) => ({
                          value: alternative.alternativeId ?? alternative.suggestionId ?? '',
                          label: structureAlternativeLabel(alternative),
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
                              (Boolean(item.payload.standardRequired) && Boolean(item.payload.requiresStandardConfirmation)) ||
                              (Boolean(item.payload.candidateOnly) && alternatives.length <= 1 && !structuralCandidate) ||
                              (Boolean(item.payload.reviewRequired) && alternatives.length <= 1 && !structuralCandidate) ||
                              (Boolean(item.payload.physicalStructureOnly) && alternatives.length <= 1 && !structuralCandidate) ||
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
        }))}
        {!review.regions?.length && !items.length && !(filter === 'ALL' || filter === 'QUALITY') && (
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

function filterRegionTree(
  regions: RecognitionRegionNode[],
  filter: ReviewFilter,
  group: string,
) {
  if (filter === 'QUALITY') return [];
  return regions
    .map((region) => {
      const fields = (region.fields ?? []).filter((field) => {
        const matchesGroup = group === 'ALL' || field.groupName === group;
        const matchesStatus =
          filter === 'ALL' ||
          (filter === 'LOW' && field.confidence < 0.65 && field.status !== 'IGNORED') ||
          field.status === filter;
        return matchesGroup && matchesStatus;
      });
      return { ...region, fields };
    })
    .filter((region) => {
      if (filter === 'ALL') return true;
      return region.fields.length > 0 || region.alternatives.length > 1;
    });
}

function RecognitionRegionTree({
  regions,
  review,
  editable,
  busy,
  selectedRecognitionItemId,
  selectedAlternatives,
  onSelectAlternative,
  onSelect,
  onConfirm,
  onModify,
  onIgnore,
  onRestore,
}: {
  regions: RecognitionRegionNode[];
  review: RecognitionReview;
  editable: boolean;
  busy?: boolean;
  selectedRecognitionItemId?: string;
  selectedAlternatives: Record<string, string>;
  onSelectAlternative: (regionId: string, alternativeId: string) => void;
  onSelect: (item: RecognitionReviewItem) => void;
  onConfirm: (item: RecognitionReviewItem, selectedAlternativeId?: string) => void;
  onModify: (item: RecognitionReviewItem) => void;
  onIgnore: (item: RecognitionReviewItem) => void;
  onRestore: (item: RecognitionReviewItem) => void;
}) {
  if (!regions.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有符合条件的区域" />;
  return (
    <div className="recognition-region-tree" aria-label="区域字段树">
      {regions.map((region) => {
        const rootItems = review.items.filter((item) =>
          item.suggestionIds.some((id) => region.alternatives.some((alternative) =>
            alternative.regions.some((candidate) => candidate.suggestionId === id))),
        );
        const rootItem = rootItems[0];
        const selectedAlternativeId = selectedAlternatives[region.regionId] || region.alternatives[0]?.alternativeId;
        const status = regionStatus(region.status);
        const isSimpleLongTable = rootItem?.source === 'RULE'
          && rootItem.kind === 'ROW_TABLE'
          && rootItem.payload.reasonCode === 'SIMPLE_LONG_TABLE';
        const singleRegionSemanticAction = !isSimpleLongTable
          && region.alternatives.length === 1
          && Boolean(rootItem)
          && (region.fields.length === 0
            || region.canonicalStatus !== 'CONFIRMED'
            || region.structureStatus !== 'CONFIRMED');
        const resolutionGroupId = region.resolutionGroupId;
        const isPrimaryResolutionCard = !resolutionGroupId
          || regions.find((candidate) => candidate.resolutionGroupId === resolutionGroupId)?.regionId === region.regionId;
        const visibleFields = deduplicateRegionFields(region.fields);
        const regionKey = `${region.sheetId || region.sheetName || ''}|${region.range || ''}|${region.kind}`;
        return (
          <section className="recognition-region-card" key={regionKey} data-status={status.toLowerCase()}>
            <div className="recognition-region-heading">
              <button
                type="button"
                className="recognition-review-row recognition-region-row"
                onClick={() => rootItem && onSelect(rootItem)}
              >
                <StatusIndicator status={status} />
                <span className="recognition-row-content">
                  <strong>{region.fieldName || '未命名区域'}</strong>
                  <span>{blockTypeLabel(region.kind)} · {region.sheetName || region.sheetId || '当前工作表'} · {region.range || '范围待确认'}</span>
                </span>
                <span className="recognition-row-status">{regionStatusLabel(region)}</span>
              </button>
              {region.alternatives.length > 1 && isPrimaryResolutionCard && (
                <div className="field-property-alert full recognition-structure-alternatives" role="group" aria-label="结构方案选择">
                  <strong>结构方案（{region.alternatives.length}）</strong>
                  <Select
                    aria-label="选择结构方案"
                    value={selectedAlternativeId}
                    onChange={(value) => onSelectAlternative(region.regionId, value)}
                    options={region.alternatives.map((alternative) => ({
                      value: alternative.alternativeId,
                      label: regionAlternativeLabel(alternative),
                    }))}
                  />
                  {rootItem && (
                    <Button
                      size="small"
                      type="primary"
                      disabled={!editable || busy || !selectedAlternativeId}
                      onClick={() => onConfirm(rootItem, selectedAlternativeId)}
                    >
                      确认结构并识别字段
                    </Button>
                  )}
                </div>
              )}
              {region.alternatives.length > 1 && !isPrimaryResolutionCard && (
                <span className="recognition-structure-group-note">同一冲突组的组合方案请在首个区域确认</span>
              )}
              {singleRegionSemanticAction && rootItem && selectedAlternativeId && (
                <div className="recognition-region-semantic-action">
                  <Button
                    size="small"
                    type="primary"
                    disabled={!editable || busy}
                    onClick={() => onConfirm(rootItem, selectedAlternativeId)}
                  >
                    {region.fields.length === 0 && region.canonicalStatus === 'CONFIRMED'
                      ? '重新识别区域字段'
                      : '采用此区域并识别字段'}
                  </Button>
                </div>
              )}
            </div>
            {region.kind === 'MATRIX' && Boolean(region.structures?.matrixModel) && (
              <MatrixStructureSummary model={region.structures?.matrixModel as MatrixModel} title="矩阵结构" />
            )}
            {visibleFields.length > 0 && (
              <div className="recognition-region-fields">
                <div className="recognition-region-subheading">
                  <strong>{isSimpleLongTable ? '表头字段，请逐项确认' : '字段'}</strong>
                  <span>{visibleFields.length} 个</span>
                </div>
                {visibleFields.map((field) => (
                  <RegionFieldCard
                    key={field.id}
                    field={field}
                    selected={field.id === selectedRecognitionItemId}
                    editable={editable}
                    busy={busy}
                    onSelect={onSelect}
                    onConfirm={onConfirm}
                    onModify={onModify}
                    onIgnore={onIgnore}
                    onRestore={onRestore}
                  />
                ))}
              </div>
            )}
            {(region.recordSlots?.length ?? 0) > 0 && (
              <details className="recognition-region-runtime" open>
                <summary>步骤记录槽（{region.recordSlots?.length ?? 0}）</summary>
                <div>{region.recordSlots?.map((slot) => (
                  <span key={slot.slotId}>第 {slot.order ?? 0} 条 · {slot.range || slot.identityAddress || slot.slotId}</span>
                ))}</div>
              </details>
            )}
            {region.runtimeSlots.filter((slot) => !(region.recordSlots ?? []).some((record) => record.slotId === slot.slotId)).length > 0 && (
              <details className="recognition-region-runtime">
                <summary>运行时成员槽位（{region.runtimeSlots.filter((slot) => !(region.recordSlots ?? []).some((record) => record.slotId === slot.slotId)).length}）</summary>
                <div>{region.runtimeSlots
                  .filter((slot) => !(region.recordSlots ?? []).some((record) => record.slotId === slot.slotId))
                  .map((slot) => <span key={slot.slotId}>{slot.column || slot.identityAddress || slot.slotId}</span>)}</div>
              </details>
            )}
            {(region.staticContents?.length ?? 0) > 0 && (
              <details className="recognition-region-audit">
                <summary>静态内容（{region.staticContents?.length ?? 0}）</summary>
                <div>{region.staticContents?.map((content, index) => (
                  <span key={`${content.address ?? 'static'}-${index}`}>{content.address ? `${content.address} · ` : ''}{content.text || '静态说明'}</span>
                ))}</div>
              </details>
            )}
          </section>
        );
      })}
    </div>
  );
}

function RegionFieldCard({
  field,
  selected,
  editable,
  busy,
  onSelect,
  onConfirm,
  onModify,
  onIgnore,
  onRestore,
}: {
  field: RecognitionRegionNode['fields'][number];
  selected: boolean;
  editable: boolean;
  busy?: boolean;
  onSelect: (item: RecognitionReviewItem) => void;
  onConfirm: (item: RecognitionReviewItem, selectedAlternativeId?: string) => void;
  onModify: (item: RecognitionReviewItem) => void;
  onIgnore: (item: RecognitionReviewItem) => void;
  onRestore: (item: RecognitionReviewItem) => void;
}) {
  const attributes = field.attributes ?? field.payload;
  const reviewRequired = field.status !== 'CONFIRMED'
    && Boolean(attributes.reviewRequired ?? field.payload.reviewRequired ?? field.payload.candidateOnly);
  return (
    <div className="recognition-field-card" data-status={field.status.toLowerCase()}>
      <button type="button" className="recognition-review-row recognition-field-row" aria-expanded={selected} onClick={() => onSelect(field)}>
        <StatusIndicator status={regionStatus(field.status)} />
        <span className="recognition-row-content">
          <strong>{field.fieldName || '字段名称待人工命名'}</strong>
          <span>{field.payload.suggestionLevel === 'CHILD' ? '明细字段' : kindLabel(field.kind)}{reviewRequired ? ' · 待确认' : ''}</span>
        </span>
        <span className="recognition-row-location"><EnvironmentOutlined aria-hidden="true" /> {locationText(field)}</span>
      </button>
      {selected && (
        <div className="recognition-review-expanded">
          <dl className="recognition-field-attributes">
            <div><dt>类型</dt><dd>{valueTypeLabel(attributeText(attributes.valueType, field.valueType ?? 'string'))}</dd></div>
            <div><dt>单位</dt><dd>{attributeText(attributes.unit, field.payload.unit ?? '') || '未设置'}</dd></div>
            <div><dt>单元格位置</dt><dd>{textValue((attributes.locator as Record<string, unknown> | undefined)?.labelAddress) || field.labelAddress || '—'} / {field.address || '—'}</dd></div>
          </dl>
          {reviewRequired && <div className="field-property-alert full"><strong>字段待确认</strong><span>请确认字段名称和填写位置，确认后才会进入正式模板。</span></div>}
          <div className="recognition-review-actions">
            {field.status === 'IGNORED' ? (
              <Button size="small" icon={<ReloadOutlined />} disabled={!editable || busy} onClick={() => onRestore(field)}>恢复</Button>
            ) : (
              <>
                {field.status !== 'CONFIRMED' && <Button size="small" type="primary" icon={<CheckOutlined />} disabled={!editable || busy || (Boolean(field.payload.standardRequired) && Boolean(field.payload.requiresStandardConfirmation))} onClick={() => onConfirm(field)}>确认</Button>}
                <Button size="small" icon={<EditOutlined />} disabled={!editable || busy} onClick={() => onModify(field)}>修改</Button>
                <Popconfirm title={`忽略“${field.fieldName || '该字段'}”？`} okText="忽略" cancelText="取消" onConfirm={() => onIgnore(field)}>
                  <Button size="small" danger type="text" icon={<EyeInvisibleOutlined />} disabled={!editable || busy}>忽略</Button>
                </Popconfirm>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function regionStatus(status: string): RecognitionReviewStatus {
  return status === 'CONFIRMED' || status === 'CONFLICT' || status === 'IGNORED' ? status : 'PENDING';
}

function deduplicateRegions(regions: RecognitionRegionNode[]) {
  const byGeometry = new Map<string, RecognitionRegionNode>();
  for (const region of regions) {
    const key = `${region.sheetId || region.sheetName || ''}|${region.range || ''}|${region.kind}`;
    const normalized = { ...region, fields: deduplicateRegionFields(region.fields ?? []) };
    const current = byGeometry.get(key);
    if (!current || regionCandidateScore(normalized) > regionCandidateScore(current)) {
      byGeometry.set(key, normalized);
    }
  }
  return Array.from(byGeometry.values());
}

function deduplicateRegionFields(fields: RecognitionRegionNode['fields']) {
  const byLocation = new Map<string, RecognitionRegionNode['fields'][number]>();
  for (const field of fields) {
    const locator = field.payload.locator as Record<string, string | undefined> | undefined;
    const key = `${locator?.sheetId || field.sheetId || ''}|${locator?.valueRange || locator?.logicalInputRange || field.address || ''}|${field.payload.role || 'FIELD'}`;
    const current = byLocation.get(key);
    if (!current || fieldCandidateScore(field) > fieldCandidateScore(current)) byLocation.set(key, field);
  }
  return Array.from(byLocation.values());
}

function regionCandidateScore(region: RecognitionRegionNode) {
  let score = deduplicateRegionFields(region.fields ?? []).length * 2;
  if (region.canonicalStatus === 'CONFIRMED') score += 20;
  if (region.structureStatus === 'CONFIRMED') score += 20;
  if (region.status === 'CONFIRMED') score += 10;
  return score;
}

function fieldCandidateScore(field: RecognitionRegionNode['fields'][number]) {
  let score = 0;
  if (field.payload.recognitionOrigin === 'CANONICAL_FIELD_ASSEMBLER'
    || field.payload.recognitionOrigin === 'CANONICAL_FORM_ASSEMBLER') score += 100;
  if (field.payload.activeGenerationId) score += 40;
  if (field.payload.labelPath) score += 20;
  if (field.status === 'CONFIRMED') score += 10;
  if (field.payload.candidateOnly === false) score += 5;
  return score;
}

function regionStatusLabel(region: RecognitionRegionNode) {
  if (region.structureStatus === 'CONFLICT') return '结构冲突，待选择';
  if (region.kind === 'UNKNOWN' || region.structureStatus === 'UNRESOLVED') return '物理证据不足，待复核';
  if (region.status === 'CONFIRMED' && region.reviewRequired) return '结构已确认，字段待复核';
  return statusLabel(regionStatus(region.status));
}

function regionAlternativeLabel(alternative: RecognitionRegionNode['alternatives'][number]) {
  const source = alternative.source === 'PHYSICAL' ? '物理判断' : alternative.source === 'MODEL' ? '模型判断' : '结构方案';
  const regions = alternative.regions.map((item) => {
    const geometry = [
      item.recordAxis ? `按${item.recordAxis === 'COLUMN' ? '列' : item.recordAxis === 'ROW' ? '行' : item.recordAxis}` : '',
      item.headerRange ? `表头 ${item.headerRange}` : '',
      item.dataRange ? `数据 ${item.dataRange}` : '',
      item.rowHeaderRange ? `行轴 ${item.rowHeaderRange}` : '',
      item.columnHeaderRange ? `列轴 ${item.columnHeaderRange}` : '',
      item.crossDataRange ? `交叉 ${item.crossDataRange}` : '',
    ].filter(Boolean).join(' · ');
    return `${blockTypeLabel(item.kind ?? '')} ${item.range || '范围待确认'}${geometry ? `（${geometry}）` : ''}`;
  });
  return `${source}：${regions.join(' + ')}`;
}

function attributeText(value: unknown, fallback?: unknown) {
  const candidate = value ?? fallback;
  return typeof candidate === 'string' || typeof candidate === 'number' || typeof candidate === 'boolean'
    ? String(candidate)
    : '';
}

function MatrixStructureSummary({ model, title }: { model: MatrixModel; title: string }) {
  const isRowProjection = model.recordAxis === 'ROW';
  const slots = isRowProjection ? (model.rowSlots ?? []) : (model.columnSlots ?? []);
  const incomplete = !model.rowHeaderRange || !model.columnHeaderRange || !model.crossDataRange;
  const rowAxisNames = [...(model.rowDimensions ?? []), ...(model.rowAttributes ?? [])]
    .map((axis) => axis.name)
    .filter(Boolean);
  const measures = (model.bindings ?? [])
    .filter((binding) => binding.bindingKind === 'MEASURE')
    .map((binding) => binding.name)
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
          <dt>列成员轴</dt>
          <dd>{model.columnHeaderRange || '待确认'} · 运行时成员槽位</dd>
        </div>
        <div>
          <dt>交叉值填写区域</dt>
          <dd>{measures.join('、') || '交叉指标待命名'} · {model.crossDataRange || '待确认'}</dd>
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

type StructureAlternative = NonNullable<
  RecognitionReviewItem['payload']['structureAlternatives']
>[number];

function structureAlternativeLabel(alternative: StructureAlternative) {
  const source =
    alternative.source === 'PHYSICAL'
      ? '物理判断'
      : alternative.source === 'MODEL'
        ? '模型候选'
        : '结构方案';
  const regions =
    alternative.regions?.length
      ? alternative.regions
      : [{
          suggestionId: alternative.suggestionId ?? '',
          kind: alternative.kind,
          range: alternative.range,
        }];
  const members = regions
    .map((region) => `${blockTypeLabel(region.kind ?? '')} ${region.range || '坐标待确认'}`)
    .join(' + ');
  return `${source}：${members}`;
}

function isStructuralKind(kind: RecognitionReviewItem['kind']) {
  return ['FORM_REGION', 'ROW_TABLE', 'COLUMN_TABLE', 'MATRIX', 'TABLE_REGION'].includes(kind);
}

function coverageDescription(coverage?: RecognitionReview['recognitionCoverage']) {
  if (!coverage) return '识别结果不是完整业务语义，确认前不会作为正式字段结构使用。';
  const expected = coverage.expectedRegionCount ?? coverage.physicalRegionCount ?? 0;
  const covered = coverage.coveredRegionCount ?? 0;
  const unresolved = coverage.unresolvedRegionCount ?? Math.max(0, expected - covered);
  if (expected > 0 && covered >= expected && unresolved === 0) {
    return `结构覆盖已完成（${covered}/${expected}）；字段候选仍需逐项确认后才会进入正式 Mapping。`;
  }
  return `已覆盖 ${covered}/${expected} 个物理区域，仍有 ${unresolved} 个区域待确认；当前候选不会被视为完整识别结果。`;
}

function isCoverageComplete(coverage?: RecognitionReview['recognitionCoverage']) {
  if (!coverage) return false;
  const expected = coverage.expectedRegionCount ?? coverage.physicalRegionCount ?? 0;
  const covered = coverage.coveredRegionCount ?? 0;
  const unresolved = coverage.unresolvedRegionCount ?? Math.max(0, expected - covered);
  return expected > 0 && covered >= expected && unresolved === 0;
}

function statusLabel(status: RecognitionReviewStatus) {
  if (status === 'CONFIRMED') return '已确认';
  if (status === 'CONFLICT') return '需要确认含义';
  if (status === 'IGNORED') return '已忽略';
  return '待确认';
}

function nameSourceLabel(source: string) {
  switch (source) {
    case 'ROW_ATTRIBUTE_FALLBACK':
      return '行属性名称';
    case 'PHYSICAL_HEADER_FALLBACK':
      return '表头识别';
    case 'RUNTIME_SLOT':
      return '预留填写位置';
    case 'MODEL':
      return '智能识别';
    case 'GENERATED_PLACEHOLDER':
      return '待人工命名';
    default:
      return '智能识别';
  }
}

function valueTypeLabel(value: string) {
  const labels: Record<string, string> = {
    string: '文本', STRING: '文本', number: '数字', NUMBER: '数字', integer: '整数',
    date: '日期', DATE: '日期', datetime: '日期时间', DATETIME: '日期时间',
    boolean: '是/否', BOOLEAN: '是/否', array: '多条记录', ARRAY: '多条记录',
  };
  return labels[value] ?? '文本';
}

function kindLabel(kind: RecognitionReviewItem['kind']) {
  if (kind === 'FORM_REGION') return '表单区域';
  if (kind === 'ROW_TABLE') return '明细表';
  if (kind === 'COLUMN_TABLE') return '横向明细表';
  if (kind === 'MATRIX') return '矩阵表';
  if (kind === 'TABLE_REGION') return '表格区域';
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

function isActionableTemplateIssue(issue: TemplateQualityIssue) {
  if (isInternalRecoveryIssue(issue)) return false;
  const auditOnlyTypes = new Set([
    'OTHER',
    'INVALID_STRUCTURE_PROPOSAL',
    'MISSING_TABLE_GEOMETRY',
    'MISSING_MATRIX_GEOMETRY',
    'PROTOCOL_DEFAULT_APPLIED',
    'STRUCTURE_CONFLICT',
    'INVALID_FIELD_RELATION',
    'INVALID_REGION_SEMANTICS',
  ]);
  if (auditOnlyTypes.has(issue.issueType)) return false;
  return Boolean(issue.address || issue.autoFixable || qualityPreview(issue));
}

function isCellPatchOperation(value: unknown): value is Record<string, unknown> {
  return (
    Boolean(value) &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    (value as Record<string, unknown>).op === 'SET_CELL'
  );
}
