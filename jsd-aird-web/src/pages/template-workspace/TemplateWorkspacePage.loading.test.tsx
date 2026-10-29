import { render, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { AppProviders } from '@/app/providers/AppProviders';
import { TemplateWorkspacePage } from './TemplateWorkspacePage';

const { api, preload } = vi.hoisted(() => ({
  api: {
    getEditModel: vi.fn().mockResolvedValue({
      format: 'XLSX', templateId: 'template-1', versionId: 'version-1',
      snapshotFileId: 'snapshot-1', snapshotHash: 'hash-1',
    }),
    listVersions: vi.fn(() => new Promise(() => {})),
    review: vi.fn(() => new Promise(() => {})),
    downloadSnapshot: vi.fn().mockResolvedValue({}),
  },
  preload: vi.fn(),
}));
vi.mock('@/services/templates/template-api', () => ({ templateApi: api }));
vi.mock('@/features/template-workspace/preload-editor', () => ({ preloadWorkspaceEditor: preload }));

describe('template workspace loading', () => {
  it('starts its snapshot and editor without waiting for history and review', async () => {
    render(
      <AppProviders>
        <MemoryRouter initialEntries={['/templates/version-1/workspace']}
          future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
          <Routes><Route path="/templates/:versionId/workspace" element={<TemplateWorkspacePage />} /></Routes>
        </MemoryRouter>
      </AppProviders>,
    );
    await waitFor(() => {
      expect(api.downloadSnapshot).toHaveBeenCalledWith('snapshot-1');
      expect(preload).toHaveBeenCalledWith('XLSX');
    });
    expect(api.listVersions).toHaveBeenCalledWith('template-1');
    expect(api.review).toHaveBeenCalledWith('version-1');
  });
});
