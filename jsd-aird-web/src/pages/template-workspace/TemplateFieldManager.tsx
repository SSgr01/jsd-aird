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
import { Button, Empty, Input, Select, Switch, Tooltip } from 'antd';
import { useEffect, useRef, useState } from 'react';

import type {
  BusinessField,
  FieldModel,
  TemplateBinding,
  TemplateFormat,
} from '@/features/template-workspace/types';
import { candidateBinding } from '@/features/template-workspace/field-model';
import type {
  RecognitionReview,
  RecognitionReviewItem,
  TemplateQualityIssue,
} from '@/services/templates/template-api';

import { normalizeAddress, validateAddress } from './coordinates';
import { RecognitionReviewPanel } from './RecognitionReviewPanel';

export type CoordinateTarget = 'labelAddress' | 'address';
export type FieldManagerTab = 'recognition' | 'structure' | 'properties';

interface Props {
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
  onConfirmRecognitionItem: (item: RecognitionReviewItem) => void;
  onModifyRecognitionItem: (item: RecognitionReviewItem) => void;
  onIgnoreRecognitionItem: (item: RecognitionReviewItem) => void;
  onRestoreRecognitionItem: (item: RecognitionReviewItem) => void;
  onSelectQualityIssue: (issue: TemplateQualityIssue) => void;
  onApplyQualityIssue: (issue: TemplateQualityIssue) => void;
  onIgnoreQualityIssue: (issue: TemplateQualityIssue) => void;
  onRollbackQualityIssue: (issue: TemplateQualityIssue) => void;
  onSelectField: (field: BusinessField) => void;
  onUpdateField: (fieldId: string, update: Partial<BusinessField>) => void;
  onUpdateCoordinates: (
    fieldId: string,
    update: Partial<Record<CoordinateTarget, string>>,
  ) => void;
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

export function TemplateFieldManager({
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
    : selectedField ? candidateBinding(selectedField) : undefined;

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
}: Pick<Props, 'editable' | 'fieldModel' | 'selectedFieldId' | 'onSelectField' | 'onAddField' | 'onManageGroups'>) {
  const fieldRefs = useRef(new Map<string, HTMLButtonElement>());

  useEffect(() => {
    if (!selectedFieldId) return;
    fieldRefs.current.get(selectedFieldId)?.scrollIntoView?.({ block: 'nearest' });
  }, [selectedFieldId]);

  return (
    <section className="field-structure-pane" role="tabpanel">
      <div className="field-manager-toolbar">
        <span>共 {fieldModel.fields.length} 个字段</span>
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
          const fields = fieldModel.fields.filter((field) => field.groupId === group.id);
          return (
            <details className="field-tree-group" key={group.id} open>
              <summary>
                <span><FolderOpenOutlined /> {group.name}</span>
                <span className="field-tree-group-meta">
                  <span>{fields.length}</span>
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
                  <button
                    type="button"
                    key={field.id}
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
                      <small>{kindLabel(field.kind)}{field.unit ? ` · ${field.unit}` : ''}</small>
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

function FieldProperties({
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
  const locator = binding?.locator ?? {};
  const sourceLabelAddress = text(locator.labelAddress);
  const sourceAddress = text(locator.address) || text(locator.range);
  const [labelAddress, setLabelAddress] = useState(sourceLabelAddress);
  const [address, setAddress] = useState(sourceAddress);
  const [labelError, setLabelError] = useState<string>();
  const [addressError, setAddressError] = useState<string>();
  const sheetName = text(locator.sheetName) || text(locator.sheetCode) || '当前工作表';

  useEffect(() => setLabelAddress(sourceLabelAddress), [sourceLabelAddress]);
  useEffect(() => setAddress(sourceAddress), [sourceAddress]);

  const reviewText = field.reviewStatus === 'ISSUE'
    ? '该字段存在识别冲突，请核对字段属性或 Excel 位置。'
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
          {!labelAddress && <span className="coordinate-help">尚未设置标签位置，Excel 标签与字段名称不能自动同步。</span>}
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
          {!address && <span className="coordinate-help">尚未设置填写位置，生产单数据不能写入 Excel。</span>}
          <span className="coordinate-help">可直接输入坐标，也可以切换工作表后从左侧 Excel 选择。</span>
        </div>
      </div>

      {editable && <DeleteFieldButton field={field} onDeleteField={onDeleteField} />}
    </section>
  );
}

function FieldReviewNotice({ field, text: notice }: { field: BusinessField; text: string }) {
  return (
    <div className="field-review-notice" data-tone={field.reviewStatus === 'ISSUE' ? 'conflict' : 'pending'}>
      <ExclamationCircleOutlined />
      <span>{notice}</span>
    </div>
  );
}

function BusinessPropertyFields({
  editable,
  field,
  groups,
  onUpdateField,
}: Pick<Props, 'editable' | 'onUpdateField'> & {
  field: BusinessField;
  groups: FieldModel['groups'];
}) {
  return (
    <div className="field-property-section field-property-grid">
      <div className="field-property-section-title full">基本属性</div>
      <label className="field-property full">
        <span>字段名称</span>
        <Input
          value={field.name}
          disabled={!editable}
          maxLength={100}
          status={field.name.trim() ? undefined : 'error'}
          onChange={(event) => onUpdateField(field.id, { name: event.target.value })}
        />
        {!field.name.trim() && <small role="alert">字段名称不能为空</small>}
      </label>
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
  if (kind === 'MATRIX') return '矩阵表';
  return '普通字段';
}

function text(value: unknown) {
  return typeof value === 'string' ? value : '';
}
