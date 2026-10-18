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
          message="按普通数据表导入"
          description="系统没有识别到实验编号与数据之间的关联结构。此模板仍可按普通数据表导入，原有导入方式不受影响。"
        />
      </section>
    );
  }

  return (
    <section className="experiment-semantics-pane" role="tabpanel">
      <div className="field-manager-help experiment-data-help">
        这里配置导入时如何整理实验数据：识别实验、样品、配方或批次编号，关联配方材料和用量，并归类测试结果。通常由系统自动完成，只需处理下方标出的异常。
      </div>
      <div className="experiment-semantic-summary">
        <div>
          <span>系统已识别为</span>
          <strong>实验数据模板</strong>
        </div>
        <div>
          <span>每个文件按</span>
          <strong>整份文件一个实验</strong>
        </div>
        <div>
          <span>系统自动识别</span>
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
        message={issueCount ? '实验数据已识别，请处理标出的项目' : '实验数据设置已完成'}
        description={issueCount
          ? '系统已自动整理大部分字段，无需逐项设置。请只处理下方标出的内容。'
          : '系统已根据字段名称和表格结构生成导入设置。'}
        action={(
          <Button size="small" icon={<EyeOutlined />} onClick={() => setShowDetails((value) => !value)}>
            {showDetails ? '收起结果' : '查看结果'}
          </Button>
        )}
      />

      {showDetails && (
        <div className="field-property-section experiment-semantics-list">
          <div className="field-property-section-title">系统识别到的内容</div>
          <div className="experiment-semantic-domain-list">
            {configuration.identities.length > 0 && (
              <Tag color="blue">{identityLabel}</Tag>
            )}
            {domains.map((domain) => <Tag key={domain}>{DOMAIN_LABELS[domain] ?? domain}</Tag>)}
            {configuration.listProjections.length > 0 && (
              <Tag color="purple">
                配方表格 {configuration.listProjections.length} 个（实际材料和用量以导入文件为准）
              </Tag>
            )}
          </div>
          <small>
            这里展示模板中配置的编号、配方表格和数据类别，不代表已有多少条实验记录。实际记录数量由上传文件中的填写内容决定；缺少编号的记录会单独提示。AI 模型使用哪些字段，可在模型任务中设置。
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
                <strong>{component?.name ?? '数据区域'}：原编号字段已失效</strong>
                <Select
                  value={identity.identityType}
                  disabled={!editable}
                  options={IDENTITY_TYPES}
                  onChange={(value) => updateIdentityType(identity.componentId, value)}
                />
                <Select
                  allowClear
                  placeholder="重新选择编号字段"
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
                <small>{field.experimentSemanticIssue ?? '系统无法确定该字段属于哪类实验数据，请选择。'}</small>
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
