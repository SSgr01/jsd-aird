import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ExperimentTemplateDataPanel } from './ExperimentTemplateDataPanel';
import type { ExperimentTemplateDataReview } from '@/services/experiments/experiment-api';

const review: ExperimentTemplateDataReview = {
  templateVersionId: 'template-v9',
  contractHash: 'hash',
  confirmed: false,
  values: {},
  fields: [{
    fieldCode: 'VISCOSITY', displayName: '粘度', dataType: 'NUMBER',
    required: true, identity: false, aliases: [], dataPath: '/viscosity',
  }],
  bindings: [{
    bindingId: 'viscosity-binding', fieldCode: 'VISCOSITY', dataPath: '/viscosity',
    mappingKind: 'SCALAR', recordHeight: 1, recordWidth: 1, recordStride: 1,
    locator: { sheetId: 'sheet-1', sheetName: '测试', address: 'B2' },
    required: true, identity: false, trainingEligible: true,
  }],
};

describe('ExperimentTemplateDataPanel', () => {
  it('uses the shared field tabs and blocks confirmation until required values are filled', () => {
    const onChange = vi.fn();
    const onConfirm = vi.fn();
    render(<ExperimentTemplateDataPanel review={review} values={{}} editable confirmed={false}
      busy={false} onChange={onChange} onConfirm={onConfirm} onFocus={vi.fn()} />);
    expect(screen.getByText('字段数据')).toBeInTheDocument();
    expect(screen.getByText('字段结构')).toBeInTheDocument();
    expect(screen.getByText('字段映射')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '确认并提交本次数据' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: /粘度.*未填写/ }));
    fireEvent.change(screen.getByRole('textbox', { name: '修改粘度' }), { target: { value: '1350' } });
    expect(onChange).toHaveBeenCalledWith('/viscosity', '1350');
  });

  it('keeps the tab bar and field scroll area as separate rows so the footer never covers fields', () => {
    const { container } = render(<ExperimentTemplateDataPanel review={review} values={{}} editable
      confirmed={false} busy={false} onChange={vi.fn()} onConfirm={vi.fn()} onFocus={vi.fn()} />);
    const panel = container.querySelector('.experiment-template-data-panel');
    const content = panel?.querySelector('.data-workbench-panel-content');
    expect(content?.querySelector('.data-workbench-tabs')?.nextElementSibling).toHaveClass('data-panel-body');
    expect(content?.lastElementChild).toHaveClass('data-workbench-footer');
    fireEvent.click(screen.getByRole('tab', { name: '字段映射' }));
    expect(screen.getByText(/模板字段和填写位置已锁定/)).toBeInTheDocument();
  });

  it('allows confirming populated values', () => {
    const onConfirm = vi.fn();
    render(<ExperimentTemplateDataPanel review={review} values={{ viscosity: '1350' }} editable
      confirmed={false} busy={false} onChange={vi.fn()} onConfirm={onConfirm} onFocus={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: '确认并提交本次数据' }));
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it('treats empty repeated values as missing and displays a filled value without JSON syntax', () => {
    const repeated = {
      ...review,
      fields: [{ ...review.fields[0]!, dataPath: '/records/*/viscosity' }],
      bindings: [{ ...review.bindings[0]!, dataPath: '/records/*/viscosity', mappingKind: 'REPEAT_FIELD' }],
    } satisfies ExperimentTemplateDataReview;
    const { rerender } = render(<ExperimentTemplateDataPanel review={repeated} values={{ records: [{}] }}
      editable confirmed={false} busy={false} onChange={vi.fn()} onConfirm={vi.fn()} onFocus={vi.fn()} />);
    expect(screen.getByRole('button', { name: '确认并提交本次数据' })).toBeDisabled();
    rerender(<ExperimentTemplateDataPanel review={repeated} values={{ records: [{ viscosity: '1250' }] }}
      editable confirmed={false} busy={false} onChange={vi.fn()} onConfirm={vi.fn()} onFocus={vi.fn()} />);
    expect(screen.getByRole('button', { name: '粘度 1250' })).toBeInTheDocument();
  });

  it('shows and validates bound fields from a template-imported workbook', () => {
    const snapshot = { sheets: { 'sheet-1': { name: '测试', cellData: { 1: { 1: { v: '1350' } } } } } };
    const { rerender } = render(<ExperimentTemplateDataPanel review={review} values={{}} importedSnapshot={snapshot}
      editable confirmed={false} busy={false} onChange={vi.fn()} onConfirm={vi.fn()} onFocus={vi.fn()} />);
    expect(screen.getByRole('button', { name: '粘度 1350' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '确认并提交本次数据' })).toBeEnabled();
    rerender(<ExperimentTemplateDataPanel review={review} values={{}} importedSnapshot={{
      sheets: { 'sheet-1': { name: '测试', cellData: {} } },
    }} editable confirmed={false} busy={false} onChange={vi.fn()} onConfirm={vi.fn()} onFocus={vi.fn()} />);
    expect(screen.getByRole('button', { name: '确认并提交本次数据' })).toBeDisabled();
  });
});
