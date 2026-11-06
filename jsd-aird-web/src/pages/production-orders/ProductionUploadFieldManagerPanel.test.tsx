import { render, screen } from '@testing-library/react';
import { vi, it, expect } from 'vitest';
import { AppProviders } from '@/app/providers/AppProviders';
import { ProductionUploadFieldManagerPanel } from './ProductionUploadFieldManagerPanel';

vi.mock('@/services/production-orders/production-upload-api', () => ({
  productionUploadApi: { fields: vi.fn().mockResolvedValue([]) },
}));

it('renders recognized production fields without experiment projections', async () => {
  render(
    <AppProviders>
      <ProductionUploadFieldManagerPanel
        uploadId="order-1"
        lockVersion={1}
        format="XLSX"
        recognitionResult={{
          mapping: [],
          fieldModel: { modelVersion: 5, groups: [], fields: [] },
          recognitionReview: {
            recognitionRunId: 'run-1',
            items: [],
            groups: [],
            summary: { total: 0, pending: 0, lowConfidence: 0, conflict: 0 },
          },
        }}
        canUpdate
        activeTab="recognition"
        onActiveTabChange={() => undefined}
      />
    </AppProviders>,
  );
  expect(await screen.findByRole('tab', { name: '识别确认' })).toBeInTheDocument();
});
