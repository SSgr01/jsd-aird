import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import { ExperimentImportWorkbenchPage } from './ExperimentImportWorkbenchPage';

vi.mock('@/pages/data/DataImportJobPage', () => ({
  DataImportJobPage: ({
    onExperimentReady,
    onExperimentLinksReady,
  }: {
    onExperimentReady?: (id: string) => void;
    onExperimentLinksReady?: (links: never[]) => void;
  }) => <button onClick={() => {
    onExperimentLinksReady?.([
      { experimentId: 'experiment-1' },
      { experimentId: 'experiment-2' },
    ] as never[]);
    onExperimentReady?.('experiment-1');
  }}>打开生成的实验</button>,
}));

vi.mock('./ExperimentWorkspacePage', () => ({
  ExperimentWorkspacePage: ({ experimentId, onBack }: { experimentId?: string; onBack?: () => void }) => <div>
    <span>{`experiment:${experimentId}`}</span>
    {onBack ? <button onClick={onBack}>返回导入数据</button> : null}
  </div>,
}));

function LocationProbe() {
  return <output data-testid="location">{useLocation().pathname}</output>;
}

describe('ExperimentImportWorkbenchPage', () => {
  it('switches from import confirmation to the experiment without changing route', () => {
    render(
      <MemoryRouter initialEntries={['/experiments/template-imports/job-1']}>
        <Routes>
          <Route path="/experiments/template-imports/:id" element={<ExperimentImportWorkbenchPage />} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: '打开生成的实验' }));
    expect(screen.getByText('experiment:experiment-1')).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/experiments/template-imports/job-1');

    fireEvent.click(screen.getByRole('button', { name: '返回导入数据' }));
    expect(screen.getByRole('button', { name: '打开生成的实验' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/experiments/template-imports/job-1');
  });
});
