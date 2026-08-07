import {
  AimOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  ExclamationCircleOutlined,
  FolderOpenOutlined,
  PlusOutlined,
  SettingOutlined,
  TableOutlined,
} from '@ant-design/icons';
import { Button, Empty, Input, Modal, Select, Switch, Tooltip } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { MutableRefObject } from 'react';

import type {
  BusinessField,
  FieldModel,
  TemplateBinding,
  TemplateFormat,
} from '@/features/template-workspace/types';
import {
  candidateBinding,
  templateLocalFieldCode,
} from '@/features/template-workspace/field-model';
import type {
  RecognitionReview,
  RecognitionReviewItem,
  StandardFieldOption,
  TemplateQualityIssue,
} from '@/services/templates/template-api';
import { templateApi } from '@/services/templates/template-api';

import { normalizeAddress, validateAddress } from './coordinates';
import { RecognitionReviewPanel } from './RecognitionReviewPanel';

export type CoordinateTarget = 'labelAddress' | 'address';
export type FieldManagerTab = 'recognition' | 'structure' | 'properties';

interface Props {
  versionId: string;
  editable: boolean;
  format: TemplateFormat;
  fieldModel: FieldModel;
  mapping: TemplateBinding[];
  selectedFieldId?: string;
  selectedRecognitionItemId?: string;
  selectedQualityIssueId?: string;
  picking?: { fieldId: string; target: CoordinateTarget };
  activeTab: FieldManagerTab;
  recognitionReview?: RecognitionReview;
  recognitionBusy?: boolean;
  onActiveTabChange: (tab: FieldManagerTab) => void;
  onSelectRecognitionItem: (item: RecognitionReviewItem) => void;
  onConfirmRecognitionItem: (item: RecognitionReviewItem, selectedSuggestionId?: string) => void;
  onModifyRecognitionItem: (item: RecognitionReviewItem) => void;
  onIgnoreRecognitionItem: (item: RecognitionReviewItem) => void;
  onRestoreRecognitionItem: (item: RecognitionReviewItem) => void;
  onSelectQualityIssue: (issue: TemplateQualityIssue) => void;
  onApplyQualityIssue: (issue: TemplateQualityIssue) => void;
  onIgnoreQualityIssue: (issue: TemplateQualityIssue) => void;
  onRollbackQualityIssue: (issue: TemplateQualityIssue) => void;
  onSelectField: (field: BusinessField) => void;
  onUpdateField: (fieldId: string, update: Partial<BusinessField>) => void;
  onUpdateCoordinates: (fieldId: string, update: Partial<Record<CoordinateTarget, string>>) => void;
  onPickCoordinate: (fieldId: string, target: CoordinateTarget) => void;
  onAddField: (groupId?: string) => void;
  onDeleteField: (field: BusinessField) => void;
  onManageGroups: () => void;
  onPlaceWordField: (field: BusinessField) => void;
}

const VALUE_TYPES = [
  { value: 'string', label: '文本' },
  { value: 'number', label: '数值' },
  { value: 'integer', label: '整数' },
  { value: 'date', label: '日期' },
  { value: 'datetime', label: '日期时间' },
  { value: 'time', label: '时间' },
  { value: 'duration', label: '时长' },
  { value: 'boolean', label: '是 / 否' },
];

const UI_TYPES = [
  { value: 'TEXT', label: '普通填写' },
  { value: 'SIGNATURE', label: '签名/人员' },
];

export function TemplateFieldManager({
  versionId,
  editable,
  format,
  fieldModel,
  mapping,
  selectedFieldId,
  selectedRecognitionItemId,
  selectedQualityIssueId,
  picking,
  activeTab,
  recognitionReview,
  recognitionBusy,
  onActiveTabChange,
  onSelectRecognitionItem,
  onConfirmRecognitionItem,
  onModifyRecognitionItem,
  onIgnoreRecognitionItem,
  onRestoreRecognitionItem,
  onSelectQualityIssue,
  onApplyQualityIssue,
  onIgnoreQualityIssue,
  onRollbackQualityIssue,
  onSelectField,
  onUpdateField,
  onUpdateCoordinates,
  onPickCoordinate,
  onAddField,
  onDeleteField,
  onManageGroups,
  onPlaceWordField,
}: Props) {
  const selectedField = fieldModel.fields.find((field) => field.id === selectedFieldId);
  const selectedBinding = selectedField?.bindingId
    ? mapping.find((binding) => binding.bindingId === selectedField.bindingId)
    : selectedField
      ? (candidateBinding(selectedField) ??
        (selectedField.locator
          ? {
              bindingId: `field-${selectedField.id}`,
              fieldId: selectedField.id,
              dataPath: selectedField.dataPath || '',
              role: 'FIELD' as const,
              locatorType: 'CELL_RANGE',
              locator: selectedField.locator,
              syncDirection: 'TWO_WAY' as const,
              primaryBinding: false,
              bindingStatus: 'VALID' as const,
            }
          : undefined))
      : undefined;

  const selectField = (field: BusinessField) => {
    onSelectField(field);
    onActiveTabChange('properties');
  };

  return (
    <aside className="template-field-manager" aria-label="字段管理">
      <div className="field-manager-tabs" role="tablist" aria-label="字段管理页面">
        {format === 'XLSX' && editable && (
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === 'recognition'}
            onClick={() => onActiveTabChange('recognition')}
          >
            识别确认
          </button>
        )}
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'structure'}
          onClick={() => onActiveTabChange('structure')}
        >
          字段结构
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'properties'}
          disabled={!selectedField}
          onClick={() => onActiveTabChange('properties')}
        >
          字段属性
        </button>
      </div>

      {activeTab === 'recognition' && format === 'XLSX' && editable ? (
        <RecognitionReviewPanel
          review={recognitionReview}
          editable={editable}
          selectedRecognitionItemId={selectedRecognitionItemId ?? selectedField?.recognitionItemId}
          selectedQualityIssueId={selectedQualityIssueId}
          busy={recognitionBusy}
          onSelect={onSelectRecognitionItem}
          onConfirm={onConfirmRecognitionItem}
          onModify={onModifyRecognitionItem}
          onIgnore={onIgnoreRecognitionItem}
          onRestore={onRestoreRecognitionItem}
          onSelectQualityIssue={onSelectQualityIssue}
          onApplyQualityIssue={onApplyQualityIssue}
          onIgnoreQualityIssue={onIgnoreQualityIssue}
          onRollbackQualityIssue={onRollbackQualityIssue}
        />
      ) : activeTab === 'structure' ? (
        <FieldStructure
          editable={editable}
          fieldModel={fieldModel}
          selectedFieldId={selectedFieldId}
          onSelectField={selectField}
          onAddField={onAddField}
          onManageGroups={onManageGroups}
        />
      ) : selectedField ? (
        <FieldProperties
          key={selectedField.id}
          versionId={versionId}
          editable={editable}
          format={format}
          groups={fieldModel.groups}
          field={selectedField}
          binding={selectedBinding}
          picking={picking}
          onUpdateField={onUpdateField}
          onUpdateCoordinates={onUpdateCoordinates}
          onPickCoordinate={onPickCoordinate}
          onDeleteField={onDeleteField}
          onPlaceWordField={onPlaceWordField}
        />
      ) : (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请先选择字段" />
      )}
    </aside>
  );
}

function FieldStructure({
  editable,
  fieldModel,
  selectedFieldId,
  onSelectField,
  onAddField,
  onManageGroups,
}: Pick<
  Props,
  'editable' | 'fieldModel' | 'selectedFieldId' | 'onSelectField' | 'onAddField' | 'onManageGroups'
>) {
  const fieldRefs = useRef(new Map<string, HTMLButtonElement>());

  useEffect(() => {
    if (!selectedFieldId) return;
    fieldRefs.current.get(selectedFieldId)?.scrollIntoView?.({ block: 'nearest' });
  }, [selectedFieldId]);

  return (
    <section className="field-structure-pane" role="tabpanel">
      <div className="field-manager-toolbar">
        <span>
          共 {fieldModel.fields.length} 个字段
          {fieldModel.fields.some((field) => field.candidate)
            ? `（含 ${fieldModel.fields.filter((field) => field.candidate).length} 个待确认候选）`
            : ''}
        </span>
        <div>
          <Tooltip title="管理业务分组">
            <Button
              size="small"
              icon={<SettingOutlined />}
              disabled={!editable}
              aria-label="管理业务分组"
              onClick={onManageGroups}
            />
          </Tooltip>
          <Button
            size="small"
            type="primary"
            icon={<PlusOutlined />}
            disabled={!editable}
            onClick={() => onAddField()}
          >
            新增字段
          </Button>
        </div>
      </div>

      <div className="field-group-tree">
        {fieldModel.groups.map((group) => {
          const fields = fieldModel.fields.filter(
            (field) => field.groupId === group.id && !field.parentFieldId,
          );
          return (
            <details className="field-tree-group" key={group.id} open>
              <summary>
                <span>
                  <FolderOpenOutlined /> {group.name}
                </span>
                <span className="field-tree-group-meta">
                  <span>
                    {fieldModel.fields.filter((field) => field.groupId === group.id).length}
                  </span>
                  {editable && (
                    <Tooltip title={`在“${group.name}”中新增字段`}>
                      <button
                        type="button"
                        className="field-tree-group-add"
                        aria-label={`在${group.name}中新增字段`}
                        onClick={(event) => {
                          event.preventDefault();
                          event.stopPropagation();
                          onAddField(group.id);
                        }}
                      >
                        <PlusOutlined />
                      </button>
                    </Tooltip>
                  )}
                </span>
              </summary>
              <div className="field-tree-items">
                {fields.map((field) => (
                  <div key={field.id} className="field-tree-node">
                    <FieldTreeButton
                      field={field}
                      selectedFieldId={selectedFieldId}
                      fieldRefs={fieldRefs}
                      onSelectField={onSelectField}
                    />
                    {field.kind === 'ROW_TABLE' &&
                      fieldModel.fields
                        .filter((child) => child.parentFieldId === field.id)
                        .map((child) => (
                          <div className="field-tree-children" key={child.id}>
                            <FieldTreeButton
                              field={child}
                              selectedFieldId={selectedFieldId}
                              fieldRefs={fieldRefs}
                              onSelectField={onSelectField}
                              child
                            />
                          </div>
                        ))}
                  </div>
                ))}
                {!fields.length && (
                  <span className="field-tree-empty">
                    暂无字段。可直接在 Excel 输入“名称：”和相邻内容，或点击新增字段后选择位置。
                  </span>
                )}
              </div>
            </details>
          );
        })}
      </div>
    </section>
  );
}

function FieldTreeButton({
  field,
  selectedFieldId,
  fieldRefs,
  onSelectField,
  child = false,
}: {
  field: BusinessField;
  selectedFieldId?: string;
  fieldRefs: MutableRefObject<Map<string, HTMLButtonElement>>;
  onSelectField: (field: BusinessField) => void;
  child?: boolean;
}) {
  return (
    <button
      type="button"
      className={child ? 'field-tree-child' : undefined}
      ref={(node) => {
        if (node) fieldRefs.current.set(field.id, node);
        else fieldRefs.current.delete(field.id);
      }}
      aria-current={field.id === selectedFieldId}
      onClick={() => onSelectField(field)}
    >
      <span className={`field-tree-icon kind-${field.kind.toLowerCase()}`}>
        {field.kind === 'SCALAR' ? <EditOutlined /> : <TableOutlined />}
      </span>
      <span className="field-tree-name">
        <strong>{field.name}</strong>
        <small>
          {field.requiresStandardConfirmation || field.fieldOrigin === 'PENDING_STANDARD'
            ? '待选择业务字段'
            : field.candidate
              ? '待确认候选'
              : child
                ? '明细列'
                : kindLabel(field.kind)}
          {field.unit ? ` · ${field.unit}` : ''}
        </small>
      </span>
      {field.reviewStatus === 'NEEDS_CONFIRMATION' || (field.confidence ?? 1) < 0.85 ? (
        <span className="field-tree-status needs-review" aria-label="建议核对" title="建议核对">
          <ExclamationCircleOutlined />
        </span>
      ) : (
        <span className="field-tree-status confirmed" aria-label="已识别" title="已识别">
          <CheckCircleOutlined />
        </span>
      )}
    </button>
  );
}

function FieldProperties({
  versionId,
  editable,
  format,
  groups,
  field,
  binding,
  picking,
  onUpdateField,
  onUpdateCoordinates,
  onPickCoordinate,
  onDeleteField,
  onPlaceWordField,
}: {
  versionId: string;
  editable: boolean;
  format: TemplateFormat;
  groups: FieldModel['groups'];
  field: BusinessField;
  binding?: TemplateBinding;
  picking?: { fieldId: string; target: CoordinateTarget };
  onUpdateField: Props['onUpdateField'];
  onUpdateCoordinates: Props['onUpdateCoordinates'];
  onPickCoordinate: Props['onPickCoordinate'];
  onDeleteField: Props['onDeleteField'];
  onPlaceWordField: Props['onPlaceWordField'];
}) {
  const locator = binding?.locator ?? field.locator ?? {};
  const sourceLabelAddress = text(locator.labelAddress);
  const sourceAddress = text(locator.address) || text(locator.range);
  const [labelAddress, setLabelAddress] = useState(sourceLabelAddress);
  const [address, setAddress] = useState(sourceAddress);
  const [labelError, setLabelError] = useState<string>();
  const [addressError, setAddressError] = useState<string>();
  const sheetName = text(locator.sheetName) || text(locator.sheetCode) || '当前工作表';

  useEffect(() => setLabelAddress(sourceLabelAddress), [sourceLabelAddress]);
  useEffect(() => setAddress(sourceAddress), [sourceAddress]);

  const reviewText =
    field.reviewStatus === 'ISSUE'
      ? '该字段存在识别冲突，请核对字段属性或 Excel 位置。'
      : field.fieldOrigin === 'PENDING_STANDARD'
        ? '该字段尚未确定业务含义，请选择标准字段，或转为模板自定义字段。'
        : field.reviewStatus === 'NEEDS_CONFIRMATION' || (field.confidence ?? 1) < 0.85
          ? '系统建议核对名称或位置，修改后会自动记为已确认。'
          : undefined;

  const commitCoordinate = (target: CoordinateTarget, rawValue: string) => {
    const value = normalizeAddress(rawValue);
    const error = validateAddress(value, target === 'labelAddress');
    if (target === 'labelAddress') {
      setLabelAddress(value);
      setLabelError(error);
    } else {
      setAddress(value);
      setAddressError(error);
    }
    onUpdateCoordinates(field.id, { [target]: value });
  };

  if (format === 'DOCX') {
    return (
      <section className="field-properties-pane" role="tabpanel">
        <div className="field-properties-scroll">
          {reviewText && <FieldReviewNotice field={field} text={reviewText} />}
          <BusinessPropertyFields
            versionId={versionId}
            editable={editable}
            field={field}
            groups={groups}
            onUpdateField={onUpdateField}
          />
          <div className="word-position-note">
            Word 字段位置跟随正文中的内容控件保存，不使用 Excel 单元格坐标。
          </div>
          {editable && (
            <Button block icon={<AimOutlined />} onClick={() => onPlaceWordField(field)}>
              在当前正文位置插入字段
            </Button>
          )}
        </div>
        {editable && <DeleteFieldButton field={field} onDeleteField={onDeleteField} />}
      </section>
    );
  }

  return (
    <section className="field-properties-pane" role="tabpanel">
      <div className="field-properties-scroll">
        {reviewText && <FieldReviewNotice field={field} text={reviewText} />}
        <BusinessPropertyFields
          versionId={versionId}
          editable={editable}
          field={field}
          groups={groups}
          onUpdateField={onUpdateField}
        />

        <div className="field-property-section">
          <div className="field-property-section-title">Excel 位置</div>
          <span className="field-sheet-name">工作表：{sheetName}</span>
          <CoordinateInput
            label="标签位置"
            value={labelAddress}
            error={labelError}
            placeholder="例如 A2"
            editable={editable}
            picking={picking?.fieldId === field.id && picking.target === 'labelAddress'}
            onChange={setLabelAddress}
            onCommit={(value) => commitCoordinate('labelAddress', value)}
            onPick={() => onPickCoordinate(field.id, 'labelAddress')}
          />
          {!labelAddress && (
            <span className="coordinate-help">
              尚未设置标签位置，Excel 标签与字段名称不能自动同步。
            </span>
          )}
          <CoordinateInput
            label="填写位置"
            value={address}
            error={addressError}
            placeholder="例如 B2 或 B7:D10"
            editable={editable}
            picking={picking?.fieldId === field.id && picking.target === 'address'}
            onChange={setAddress}
            onCommit={(value) => commitCoordinate('address', value)}
            onPick={() => onPickCoordinate(field.id, 'address')}
          />
          {!address && (
            <span className="coordinate-help">尚未设置填写位置，生产单数据不能写入 Excel。</span>
          )}
          <span className="coordinate-help">
            可直接输入坐标，也可以切换工作表后从左侧 Excel 选择。
          </span>
        </div>
      </div>

      {editable && <DeleteFieldButton field={field} onDeleteField={onDeleteField} />}
    </section>
  );
}

function FieldReviewNotice({ field, text: notice }: { field: BusinessField; text: string }) {
  return (
    <div
      className="field-review-notice"
      data-tone={field.reviewStatus === 'ISSUE' ? 'conflict' : 'pending'}
    >
      <ExclamationCircleOutlined />
      <span>{notice}</span>
    </div>
  );
}

function BusinessPropertyFields({
  versionId,
  editable,
  field,
  groups,
  onUpdateField,
}: Pick<Props, 'versionId' | 'editable' | 'onUpdateField'> & {
  field: BusinessField;
  groups: FieldModel['groups'];
}) {
  const [standardFields, setStandardFields] = useState<StandardFieldOption[]>([]);
  const [standardKeyword, setStandardKeyword] = useState('');
  const [standardLoading, setStandardLoading] = useState(false);
  const [requestOpen, setRequestOpen] = useState(false);
  const [requestDescription, setRequestDescription] = useState(field.description || '');
  const [requesting, setRequesting] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const keyword = standardKeyword.trim() || (field.name ?? '').trim();
    if (!versionId || !keyword) return () => undefined;
    setStandardLoading(true);
    void templateApi
      .searchStandardFields({ keyword })
      .then((items) => {
        if (!cancelled) setStandardFields(items);
      })
      .catch(() => {
        if (!cancelled) setStandardFields([]);
      })
      .finally(() => {
        if (!cancelled) setStandardLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [field.name, standardKeyword, versionId]);

  useEffect(() => setRequestDescription(field.description || ''), [field.id, field.description]);

  const selectStandardField = (option: StandardFieldOption) => {
    const group = groups.find(
      (item) => item.groupCode === option.groupCode || item.name === option.groupName,
    );
    onUpdateField(field.id, {
      fieldCode: option.fieldCode,
      standardFieldId: option.id,
      standardFieldVersion: option.version,
      standardFieldName: option.displayName,
      fieldOrigin: 'STANDARD',
      standardSelectionStatus: 'CONFIRMED',
      standardMatchStatus: 'CONFIRMED',
      requiresStandardConfirmation: false,
      uiType: option.uiType || 'TEXT',
      valueType: option.valueType,
      ...(group ? { groupId: group.id } : {}),
      ...(option.defaultUnit !== undefined ? { unit: option.defaultUnit || undefined } : {}),
    });
  };

  const chooseTemplateLocal = () => {
    onUpdateField(field.id, {
      fieldCode: templateLocalFieldCode(versionId, field),
      fieldOrigin: 'TEMPLATE_LOCAL',
      standardSelectionStatus: 'CUSTOM',
      standardMatchStatus: 'CONFIRMED',
      requiresStandardConfirmation: false,
      standardFieldId: undefined,
      standardFieldVersion: undefined,
      standardFieldName: undefined,
    });
  };

  const submitStandardRequest = async () => {
    setRequesting(true);
    try {
      await templateApi.requestStandardField({
        templateVersionId: versionId,
        fieldId: field.id,
        displayName: field.name,
        valueType: field.valueType,
        uiType: field.uiType || 'TEXT',
        groupCode: groups.find((group) => group.id === field.groupId)?.groupCode,
        description: requestDescription,
      });
      onUpdateField(field.id, {
        fieldCode: undefined,
        standardFieldId: undefined,
        standardFieldVersion: undefined,
        standardFieldName: undefined,
        fieldOrigin: 'PENDING_STANDARD',
        standardSelectionStatus: 'REQUESTED',
        requiresStandardConfirmation: true,
      });
      setRequestOpen(false);
      Modal.success({
        title: '已提交标准字段申请',
        content: '管理员审核通过后，可在这里选择新的标准字段。',
      });
    } catch (error) {
      Modal.error({
        title: '提交申请失败',
        content: error instanceof Error ? error.message : '请稍后重试',
      });
    } finally {
      setRequesting(false);
    }
  };

  return (
    <>
      <div className="field-property-section field-property-grid">
        <div className="field-property-section-title full">基本属性</div>
        <label className="field-property full">
          <span>字段名称</span>
          <Input
            value={field.name}
            disabled={!editable}
            maxLength={100}
            status={(field.name ?? '').trim() ? undefined : 'error'}
            onChange={(event) => onUpdateField(field.id, { name: event.target.value })}
          />
          {!(field.name ?? '').trim() && <small role="alert">字段名称不能为空</small>}
        </label>
        <label className="field-property full">
          <span>业务字段</span>
          <Select
            showSearch
            allowClear
            disabled={!editable}
            loading={standardLoading}
            value={field.standardFieldId}
            placeholder="搜索并选择标准字段"
            optionFilterProp="label"
            filterOption={false}
            options={standardFields.map((option) => ({
              value: option.id,
              label: `${option.displayName} · ${option.fieldCode}`,
            }))}
            onSearch={setStandardKeyword}
            onChange={(value) => {
              const option = standardFields.find((item) => item.id === value);
              if (option) selectStandardField(option);
            }}
          />
          <small>选择后系统自动填写标准编码、字段类型和分组。</small>
        </label>
        {editable &&
          (field.requiresStandardConfirmation || field.fieldOrigin === 'PENDING_STANDARD') && (
            <div className="field-property-actions full">
              <Button onClick={chooseTemplateLocal}>作为模板自定义字段</Button>
              <Button onClick={() => setRequestOpen(true)}>申请新增标准字段</Button>
            </div>
          )}
        <label className="field-property full">
          <span>标准字段编码</span>
          <Input value={field.fieldCode || '保存后自动生成'} disabled />
          <small>编码由系统生成，用户不需要手工填写。</small>
        </label>
        {field.semanticConflict && (
          <div className="field-property-alert full" role="alert">
            {field.conflictMessage || '这个字段存在两种可能含义，请先修改标准字段编码后再确认。'}
          </div>
        )}
        {field.requiresStandardConfirmation && !field.semanticConflict && (
          <div className="field-property-alert full" role="status">
            尚未选择标准字段。请在上方选择业务字段，或转为模板自定义字段后再保存。
          </div>
        )}
        {field.mappingKind === 'MATRIX_REGION' && (
          <div className="field-property-layout full">
            <span className="field-property-section-title">交叉表结构</span>
            <small>类型：交叉测试表</small>
            <small>记录方向：{field.repeatAxis === 'COLUMN' ? '按列；每列代表一个列成员' : field.repeatAxis === 'ROW' ? '按行；每行代表一个行成员' : '待确认'}</small>
            <small>列成员名称：{locationDisplay(field.locator?.columnHeaderRange)}</small>
            <small>行维度及属性：{locationDisplay(field.locator?.rowHeaderRange)}</small>
            <small>交叉值区域：{locationDisplay(field.locator?.crossDataRange)}</small>
          </div>
        )}
        {field.mappingKind === 'REPEAT_REGION' && (
          <div className="field-property-layout full">
            <span className="field-property-section-title">重复区域规则</span>
            <small>填写方向：{field.repeatAxis === 'COLUMN' ? '按列' : '按行'}</small>
            <small>
              每条记录：{field.recordHeight || 1} 行 × {field.recordWidth || 1} 列；步长{' '}
              {field.recordStride || 1}
            </small>
            <small>
              数据范围：
              {locationDisplay(field.locator?.dataRange, field.valueRange, field.locator?.address)}
            </small>
            <small>用户可以手动插入行/列，自动新增只在填写末条记录后提供便捷扩展。</small>
          </div>
        )}
        <label className="field-property">
          <span>字段分组</span>
          <Select
            value={field.groupId}
            disabled={!editable}
            options={groups.map((group) => ({ value: group.id, label: group.name }))}
            onChange={(value) => onUpdateField(field.id, { groupId: value })}
          />
        </label>
        <label className="field-property">
          <span>字段类型</span>
          <Select
            value={field.valueType}
            disabled={!editable || field.kind !== 'SCALAR'}
            options={VALUE_TYPES}
            onChange={(value) => onUpdateField(field.id, { valueType: value })}
          />
        </label>
        <label className="field-property">
          <span>填写方式</span>
          <Select
            value={field.uiType || 'TEXT'}
            disabled={!editable}
            options={UI_TYPES}
            onChange={(value) => onUpdateField(field.id, { uiType: value })}
          />
        </label>
        <label className="field-property">
          <span>单位</span>
          <Input
            value={field.unit}
            disabled={!editable}
            maxLength={40}
            placeholder="可留空"
            onChange={(event) => onUpdateField(field.id, { unit: event.target.value })}
          />
        </label>
        <label className="field-property required-property">
          <span>生产单必填</span>
          <Switch
            checked={field.required}
            disabled={!editable}
            onChange={(checked) => onUpdateField(field.id, { required: checked })}
          />
        </label>
        <label className="field-property full">
          <span>填写说明</span>
          <Input.TextArea
            value={field.description}
            disabled={!editable}
            rows={3}
            maxLength={300}
            placeholder="可选，填写给业务人员看的说明"
            onChange={(event) => onUpdateField(field.id, { description: event.target.value })}
          />
        </label>
      </div>
      <Modal
        open={requestOpen}
        title="申请新增标准字段"
        okText="提交申请"
        cancelText="取消"
        confirmLoading={requesting}
        onOk={() => void submitStandardRequest()}
        onCancel={() => setRequestOpen(false)}
      >
        <p>普通用户不需要填写编码，管理员会在审核时生成统一标准编码。</p>
        <Input.TextArea
          value={requestDescription}
          rows={4}
          maxLength={300}
          placeholder="请说明这个字段的业务含义和使用场景"
          onChange={(event) => setRequestDescription(event.target.value)}
        />
      </Modal>
    </>
  );
}

function locationDisplay(...values: unknown[]) {
  return (
    values.find((value): value is string => typeof value === 'string' && value.trim().length > 0) ||
    '未定位'
  );
}

function CoordinateInput({
  label,
  value,
  error,
  placeholder,
  editable,
  picking,
  onChange,
  onCommit,
  onPick,
}: {
  label: string;
  value: string;
  error?: string;
  placeholder: string;
  editable: boolean;
  picking: boolean;
  onChange: (value: string) => void;
  onCommit: (value: string) => void;
  onPick: () => void;
}) {
  return (
    <label className="field-property coordinate-property">
      <span>{label}</span>
      <div className="coordinate-input-row">
        <Input
          value={value}
          disabled={!editable}
          placeholder={placeholder}
          status={error ? 'error' : undefined}
          onChange={(event) => onChange(event.target.value.toUpperCase())}
          onBlur={(event) => onCommit(event.target.value)}
          onPressEnter={(event) => onCommit(event.currentTarget.value)}
        />
        <Button
          icon={<AimOutlined />}
          type={picking ? 'primary' : 'default'}
          disabled={!editable}
          aria-label={`从 Excel 选择${label}`}
          onClick={onPick}
        >
          {picking ? '选取中' : '选取'}
        </Button>
      </div>
      {error && <small role="alert">{error}</small>}
    </label>
  );
}

function DeleteFieldButton({
  field,
  onDeleteField,
}: {
  field: BusinessField;
  onDeleteField: Props['onDeleteField'];
}) {
  return (
    <div className="field-properties-actions">
      <Button danger icon={<DeleteOutlined />} onClick={() => onDeleteField(field)}>
        删除字段
      </Button>
    </div>
  );
}

function kindLabel(kind: BusinessField['kind']) {
  if (kind === 'ROW_TABLE') return '明细表';
  if (kind === 'COLUMN_TABLE') return '横向明细表';
  if (kind === 'MATRIX') return '矩阵表';
  if (kind === 'FREE_TEXT') return '自由文本区';
  return '普通字段';
}

function text(value: unknown) {
  return typeof value === 'string' ? value : '';
}
