import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type { BusinessField, TemplateFormat } from '@/features/template-workspace/types';

import { TemplateFieldManager } from './TemplateFieldManager';

const makeField = (reviewStatus: BusinessField['reviewStatus'], candidate = false): BusinessField => ({
  id: 'field-product-name',
  recognitionItemId: candidate ? 'recognition-product-name' : undefined,
  groupId: 'basic',
  name: '产品名称',
  kind: 'SCALAR',
  valueType: 'string',
  required: false,
  reviewStatus,
  candidate,
});

function renderProperties(field: BusinessField, format: TemplateFormat = 'XLSX') {
  return render(
    <TemplateFieldManager
      editable
      format={format}
      fieldModel={{
        modelVersion: 1,
        groups: [{ id: 'basic', name: '基础信息', order: 0 }],
        fields: [field],
        blocks: [],
        semanticAnnotations: [],
      }}
      mapping={[]}
      selectedFieldId={field.id}
      activeTab="properties"
      experimentConfiguration={{ templateUsage: 'GENERAL_DATA', identities: [], listProjections: [] }}
      onExperimentConfigurationChange={vi.fn()}
      onActiveTabChange={vi.fn()}
      onSelectRecognitionItem={vi.fn()}
      onConfirmRecognitionItem={vi.fn()}
      onModifyRecognitionItem={vi.fn()}
      onIgnoreRecognitionItem={vi.fn()}
      onRestoreRecognitionItem={vi.fn()}
      onSelectQualityIssue={vi.fn()}
      onApplyQualityIssue={vi.fn()}
      onIgnoreQualityIssue={vi.fn()}
      onRollbackQualityIssue={vi.fn()}
      onSelectField={vi.fn()}
      onUpdateField={vi.fn()}
      onUpdateCoordinates={vi.fn()}
      onPickCoordinate={vi.fn()}
      onPickProjectionRange={vi.fn()}
      onAddField={vi.fn()}
      onAddStructuredField={vi.fn()}
      onDeleteField={vi.fn()}
      onManageGroups={vi.fn()}
      onPlaceWordField={vi.fn()}
    />,
  );
}

describe('TemplateFieldManager field actions', () => {
  it('does not display a stale group identity when its group was removed', () => {
    const missingGroup = 'IMPORT:STRUCT:sheet-1:deleted-group';
    renderProperties({ ...makeField('CONFIRMED'), groupId: missingGroup });
    expect(screen.getByText('未分组')).toBeInTheDocument();
    expect(screen.queryByText(missingGroup)).not.toBeInTheDocument();
  });
  it('does not expose an instance-local code as the field name', () => {
    const code = 'INSTANCE_LOCAL.test.FIELD_123';
    renderProperties({ ...makeField('CONFIRMED'), name: code, pathSegments: [code] });
    expect(screen.queryByDisplayValue(code)).not.toBeInTheDocument();
    expect(screen.getByDisplayValue('未命名字段')).toBeInTheDocument();
  });
  it.each(['XLSX', 'DOCX'] as const)('%s 待确认候选只能从识别确认中忽略', (format) => {
    renderProperties(makeField('NEEDS_CONFIRMATION', true), format);

    expect(screen.queryByRole('button', { name: /删除字段/ })).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('请在“识别确认”中选择忽略');
  });

  it.each(['XLSX', 'DOCX'] as const)('%s 已确认字段可从字段属性中删除', (format) => {
    renderProperties(makeField('CONFIRMED'), format);

    expect(screen.getByRole('button', { name: /删除字段/ })).toBeInTheDocument();
  });
});
