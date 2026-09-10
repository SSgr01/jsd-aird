import {
  CheckCircleOutlined,
  ExclamationCircleOutlined,
  EyeOutlined,
  SelectOutlined,
} from '@ant-design/icons';
import { Alert, Button, Select, Tag } from 'antd';
import { useState } from 'react';

import {
  EXPERIMENT_FIELD_OPTIONS,
  semanticFromValue,
  semanticValue,
} from '@/features/template-workspace/experiment-semantics';
import type {
  BusinessField,
  ExperimentIdentityRule,
  ExperimentImportConfiguration,
  FieldModel,
  TemplateBinding,
} from '@/features/template-workspace/types';

export type ProjectionRangeTarget = 'labelRange' | 'valueRange';

interface Props {
  editable: boolean;
  configuration: ExperimentImportConfiguration;
  fieldModel: FieldModel;
  mapping: TemplateBinding[];
  onChange: (configuration: ExperimentImportConfiguration) => void;
  onUpdateField: (fieldId: string, update: Partial<BusinessField>) => void;
  onPickProjectionRange?: (projectionId: string, target: ProjectionRangeTarget) => void;
}

type ComponentView = {
  componentId: string;
  name: string;
  axis: 'ROW' | 'COLUMN';
  fields: BusinessField[];
};

const DOMAIN_LABELS: Record<string, string> = {
  BASIC: '基础信息',
  FORMULA: '配方',
  PROCESS: '施工和固化条件',
  TEST: '性能测试',
  CONCLUSION: '结论',
  OTHER: '其他信息',
};

const IDENTITY_TYPES = [
  { value: 'EXPERIMENT_NO', label: '实验编号' },
  { value: 'SAMPLE_NO', label: '样品编号' },
  { value: 'FORMULA_NO', label: '配方编号' },
  { value: 'BATCH_NO', label: '批次编号' },
];

export function ExperimentSemanticsPanel({
  editable,
  configuration,
  fieldModel,
  mapping,
  onChange,
  onUpdateField,
  onPickProjectionRange,
}: Props) {
  const [showDetails, setShowDetails] = useState(false);
  const components = componentViews(fieldModel, mapping);
  // Pending semantic candidates do not necessarily have a formal binding yet,
  // so they can be absent from a component view.  They still need to appear in
  // this exception-only panel; otherwise an ambiguous field such as “备注” is
  // counted but gives the administrator no way to resolve it.
  const fields = fieldModel.fields.filter((field) => !isRegionField(field));
  const experiment = configuration.templateUsage === 'EXPERIMENT_DATA';
  const reviewFields = fields.filter((field) =>
    field.experimentSemanticStatus === 'NEEDS_REVIEW' || Boolean(field.experimentSemanticIssue));
  const structureIssues = readinessIssues(configuration);
  const invalidProjections = configuration.listProjections.filter(
    (projection) => !projection.labelRange || !projection.valueRange,
  );
  // The persisted summary describes the last saved draft. During a fresh
  // recognition run the live field model is newer, so derive the counters from
  // the live model and avoid showing stale exceptions.
  const autoConfirmed = fields.filter(
    (field) => field.experimentSemanticStatus === 'AUTO_CONFIRMED',
  ).length;
  const issueCount = reviewFields.length + structureIssues.length;
  const domains = [...new Set(fields
    .map((field) => field.experimentField?.domain)
    .filter((value): value is NonNullable<typeof value> => Boolean(value)))]
    .filter((domain) => domain !== 'OTHER');
  const identityLabel = identityStructureLabel(configuration.identities);

  const patchConfiguration = (patch: Partial<ExperimentImportConfiguration>) => {
    onChange({ ...configuration, ...patch });
  };

  const updateIdentity = (component: ComponentView, bindingId?: string) => {
    const retained = configuration.identities.filter((item) => item.componentId !== component.componentId);
    if (!bindingId) {
      patchConfiguration({ identities: retained });
      return;
    }
    const current = configuration.identities.find((item) => item.componentId === component.componentId);
    patchConfiguration({
      identities: [...retained, {
        identityType: current?.identityType ?? 'EXPERIMENT_NO',
        sourceKind: 'BINDING',
        componentId: component.componentId,
        bindingId,
      }],
    });
  };

  const updateIdentityType = (componentId: string, identityType: ExperimentIdentityRule['identityType']) => {
    patchConfiguration({
      identities: configuration.identities.map((item) =>
        item.componentId === componentId ? { ...item, identityType } : item),
    });
  };

  if (!experiment) {
    return (
      <section className="experiment-semantics-pane" role="tabpanel">
        <Alert
          showIcon
          type="info"
          message="系统识别为普通数据模板"
          description="当前没有发现能够可靠关联实验编号与实验数据的结构。普通数据模板继续使用V8，不影响现有导入。"
        />
      </section>
    );
  }

  return (
    <section className="experiment-semantics-pane" role="tabpanel">
      <div className="experiment-semantic-summary">
        <div>
          <span>系统已识别为</span>
          <strong>实验数据模板</strong>
        </div>
        <div>
          <span>实验聚合</span>
          <strong>整份文件一个实验</strong>
        </div>
        <div>
          <span>自动确认</span>
          <strong>{autoConfirmed} 项</strong>
        </div>
        <div className={issueCount ? 'has-issue' : undefined}>
          <span>需要处理</span>
          <strong>{issueCount} 项</strong>
        </div>
      </div>

      <Alert
        showIcon
        type={issueCount ? 'warning' : 'success'}
        icon={issueCount ? <ExclamationCircleOutlined /> : <CheckCircleOutlined />}
        message={issueCount ? '实验语义基本完成，仅需处理异常项' : '实验语义识别完成，无冲突'}
        description={issueCount
          ? '系统已经采用高可信结果，不需要逐字段设置。请只核对下方标记的异常。'
          : '系统已根据分组、字段名称和结构校验自动生成V9草稿配置。'}
        action={(
          <Button size="small" icon={<EyeOutlined />} onClick={() => setShowDetails((value) => !value)}>
            {showDetails ? '收起结果' : '查看结果'}
          </Button>
        )}
      />

      {showDetails && (
        <div className="field-property-section experiment-semantics-list">
          <div className="field-property-section-title">已识别内容</div>
          <div className="experiment-semantic-domain-list">
            {configuration.identities.length > 0 && (
              <Tag color="blue">{identityLabel}</Tag>
            )}
            {domains.map((domain) => <Tag key={domain}>{DOMAIN_LABELS[domain] ?? domain}</Tag>)}
            {configuration.listProjections.length > 0 && (
              <Tag color="purple">
                配方矩阵 {configuration.listProjections.length} 个（实际材料和配方数在导入时确定）
              </Tag>
            )}
          </div>
          <small>
            这里确认的是模板结构，不是实验数量；数据中心会根据上传文件中的实际填写内容生成来源记录，并单独标记缺少来源编号的记录。
            业务分组用于理解字段，模型输入和预测目标由后续任务档案决定。
          </small>
        </div>
      )}

      {(structureIssues.length > 0 || reviewFields.length > 0) && (
        <div className="field-property-section experiment-semantics-list">
          <div className="field-property-section-title">需要处理的异常</div>

          {configuration.identities.map((identity) => {
            const component = components.find((item) => item.componentId === identity.componentId);
            if (!component || component.fields.some((field) => field.bindingId === identity.bindingId)) return null;
            return (
              <div className="experiment-semantics-card" key={`invalid-${identity.componentId}`}>
                <strong>{component?.name ?? '数据区域'}：原来源编号字段已失效</strong>
                <Select
                  value={identity.identityType}
                  disabled={!editable}
                  options={IDENTITY_TYPES}
                  onChange={(value) => updateIdentityType(identity.componentId, value)}
                />
                <Select
                  allowClear
                  placeholder="重新选择来源编号字段"
                  disabled={!editable}
                  options={(component?.fields ?? []).filter((field) => field.bindingId).map((field) => ({
                    value: field.bindingId as string,
                    label: field.name,
                  }))}
                  onChange={(bindingId: string | undefined) => component && updateIdentity(component, bindingId)}
                />
              </div>
            );
          })}

          {reviewFields.map((field) => {
            const alternatives = semanticOptions(field);
            return (
              <div className="experiment-semantics-card" key={`semantic-${field.id}`}>
                <strong>{semanticFieldDisplayName(field)}</strong>
                <small>{field.experimentSemanticIssue ?? '系统无法唯一确定这个字段的实验含义。'}</small>
                <Select
                  showSearch
                  optionFilterProp="label"
                  value={semanticValue(field.experimentField)}
                  disabled={!editable}
                  options={alternatives}
                  onSelect={(value: string) => onUpdateField(field.id, {
                    experimentField: semanticFromValue(value),
                    experimentSemanticConfidence: 1,
                    experimentSemanticStatus: 'CONFIRMED',
                    experimentSemanticSource: 'HUMAN',
                    experimentSemanticAlternatives: undefined,
                    experimentSemanticIssue: undefined,
                  })}
                />
              </div>
            );
          })}

          {invalidProjections.map((projection) => (
            <div className="experiment-semantics-card" key={`projection-${projection.listProjectionId}`}>
              <strong>配方区域没有完全对齐</strong>
              <small>请直接在左侧Excel中框选材料名称区域和对应的配方数值区域。</small>
              <div className="experiment-semantics-actions">
                <Button
                  icon={<SelectOutlined />}
                  disabled={!editable || !onPickProjectionRange}
                  onClick={() => onPickProjectionRange?.(projection.listProjectionId, 'labelRange')}
                >
                  选择材料区域
                </Button>
                <Button
                  icon={<SelectOutlined />}
                  disabled={!editable || !onPickProjectionRange}
                  onClick={() => onPickProjectionRange?.(projection.listProjectionId, 'valueRange')}
                >
                  选择配方数值区域
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function semanticOptions(field: BusinessField) {
  const alternatives = field.experimentSemanticAlternatives ?? [];
  if (!alternatives.length) return EXPERIMENT_FIELD_OPTIONS;
  const allowed = new Set(alternatives.map((semantic) => semanticValue(semantic)));
  const current = semanticValue(field.experimentField);
  if (current) allowed.add(current);
  return EXPERIMENT_FIELD_OPTIONS.filter((option) => allowed.has(option.value));
}

function identityStructureLabel(identities: ExperimentIdentityRule[]) {
  if (identities.length === 1) {
    const identityType = IDENTITY_TYPES.find((item) => item.value === identities[0]?.identityType)?.label
      ?? '来源编号';
    return `${identityType}字段 1 个（实际记录数在导入时确定）`;
  }
  return `来源编号规则 ${identities.length} 个（实际记录数在导入时确定）`;
}

function semanticFieldDisplayName(field: BusinessField) {
  const itemLabel = field.experimentItemLabel?.trim();
  if (itemLabel && itemLabel !== field.name.trim()) return itemLabel;
  const segments = (field.pathSegments ?? []).map((segment) => segment.trim()).filter(Boolean);
  const meaningful = segments.filter((segment) => ![
    '测试数据记录',
    '性能测试',
    '干膜性能测试',
    '测试结果',
    '试验结果',
  ].includes(segment));
  if (meaningful.length >= 2) {
    return `${meaningful.slice(0, -1).join(' / ')}（${meaningful.at(-1)}）`;
  }
  return field.name;
}

function componentViews(fieldModel: FieldModel, mapping: TemplateBinding[]): ComponentView[] {
  return fieldModel.fields.filter(isRegionField).map((root) => {
    const rootBinding = mapping.find((item) => item.bindingId === root.bindingId);
    const componentId = text(rootBinding?.locator?.componentId)
      || rootBinding?.componentId
      || rootBinding?.bindingId
      || root.bindingId
      || root.id;
    const fields = fieldModel.fields.filter((field) => !isRegionField(field) && (
      field.parentFieldId === root.id
      || mapping.some((binding) => binding.bindingId === field.bindingId && (
        binding.parentBindingId === rootBinding?.bindingId
        || text(binding.locator?.componentId) === componentId
      ))
    ));
    const axis = root.repeatAxis === 'COLUMN'
      || root.kind === 'COLUMN_TABLE'
      || rootBinding?.repeatAxis === 'COLUMN'
      ? 'COLUMN' as const : 'ROW' as const;
    return { componentId, name: root.name || '未命名数据区域', axis, fields };
  }).filter((component) => component.fields.length > 0);
}

function readinessIssues(
  configuration: ExperimentImportConfiguration,
) {
  const issues: string[] = [];
  if (!configuration.recordMode) issues.push('实验聚合方式未确定');
  // Source identities are optional child-group keys. They never decide how many
  // Experiment aggregates are created from an uploaded file.
  for (const projection of configuration.listProjections) {
    if (!projection.labelRange || !projection.valueRange) issues.push('配方矩阵区域未完全对齐');
  }
  return issues;
}

function isRegionField(field: BusinessField) {
  return field.displayRole === 'REGION'
    || ['FORM_REGION', 'ROW_TABLE', 'COLUMN_TABLE'].includes(field.kind)
    || field.mappingKind === 'REPEAT_REGION';
}

function text(value: unknown) {
  return typeof value === 'string' ? value : '';
}
