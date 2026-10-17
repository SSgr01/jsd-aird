import { fireEvent, render, screen } from '@testing-library/react';
import { vi } from 'vitest';

import { ExperimentImportDataPanel } from './ExperimentImportDataPanel';

const snapshot = {
  sheets: {
    first: {
      name: 'Sheet1',
      cellData: { 0: { 0: { v: '实验编号' }, 1: { v: 'EXP-1' } }, 1: { 0: { v: '' } } },
    },
    second: { name: 'Sheet2', cellData: { 2: { 2: { v: 42 } } } },
  },
};

it('shows imported cells across sheets and confirms the saved workbook', () => {
  const onConfirm = vi.fn();
  render(<ExperimentImportDataPanel snapshot={snapshot} confirmed={false} editable busy={false}
    onConfirm={onConfirm} />);

  expect(screen.getByText('2 个工作表 · 3 个非空单元格')).toBeInTheDocument();
  expect(screen.getByText('EXP-1')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('tab', { name: 'Sheet2' }));
  expect(screen.getByText('C3')).toBeInTheDocument();
  expect(screen.getByText('42')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '确认并提交本次数据' }));
  expect(onConfirm).toHaveBeenCalledOnce();
});
