import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { AppProviders } from '@/app/providers/AppProviders';
import { TemplateWorkspacePage } from './TemplateWorkspacePage';

const { api } = vi.hoisted(() => ({ api: {
  getEditModel: vi.fn().mockResolvedValue({
    templateId: 'template-1', versionId: 'version-1', name: '只读模板',
    format: 'DOCX', status: 'DRAFT', schema: {}, mapping: [], data: {},
    inlineSnapshot: { body: { dataStream: 'content\r\n' }, snapshotFormatVersion: 5 },
    snapshotFormatVersion: 5, lockVersion: 0, workspaceHash: 'base',
  }),
  listVersions: vi.fn().mockResolvedValue([]),
  review: vi.fn().mockResolvedValue({ status: 'NOT_SUBMITTED', lockVersion: 0, events: [] }),
} }));
vi.mock('@/services/templates/template-api', () => ({ templateApi: api }));
vi.mock('@/components/auth/usePermission', () => ({ usePermission: () => false }));
vi.mock('@/features/template-workspace/preload-editor', () => ({ preloadWorkspaceEditor: vi.fn() }));
vi.mock('@/features/template-workspace/editor-runtime', async () => {
  const React = await import('react');
  const Editor = React.forwardRef<HTMLDivElement>((_, ref) => <div ref={ref}>文档预览</div>);
  return { DocsEditor: Editor, SheetsEditor: Editor };
});

describe('template workspace permissions', () => {
  it('opens in preview and disables edit without update permission', async () => {
    render(<AppProviders><MemoryRouter initialEntries={['/templates/version-1/workspace']}
      future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes><Route path="/templates/:versionId/workspace" element={<TemplateWorkspacePage />} /></Routes>
    </MemoryRouter></AppProviders>);
    const edit = await screen.findByRole('tab', { name: '编辑' });
    expect(edit).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('tab', { name: /预览/ })).toHaveAttribute('aria-selected', 'true');
  });
});
