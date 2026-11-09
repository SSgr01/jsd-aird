import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { bibliographyApi, type Bibliography } from '@/services/knowledge/bibliography-api';
import { BibliographyPanel } from './BibliographyPanel';

afterEach(() => { cleanup(); vi.restoreAllMocks(); });
beforeEach(() => { Object.defineProperty(window, 'matchMedia', { writable: true, value: (query: string) => ({ matches: false, media: query, onchange: null, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent: () => true }) }); });
const value: Bibliography = { documentId: 'doc', versionId: 'version', revision: 2, status: 'NEEDS_REVIEW', documentType: 'PAPER',
  accepted: { title: 'A hydrogel study', authors: [{ name: 'Jane Smith', institutions: ['University'] }] }, provenance: { title: { source: 'saved_original', anchor: { page: 1 } } }, candidates: [] };

describe('automatic bibliography display', () => {
  it('distinguishes missing data from a failed request and hides internal identifiers', async () => {
    vi.spyOn(bibliographyApi, 'get').mockResolvedValue(value);
    render(<App><BibliographyPanel documentId="doc" versionId="version" /></App>);
    await screen.findByText('A hydrogel study');
    expect(screen.getByText('Jane Smith（University）')).toBeTruthy();
    expect(screen.queryByText('version')).toBeNull();
    expect(screen.queryByText('核对书目信息')).toBeNull();
  });
  it('shows a load error without presenting an empty record as success', async () => {
    vi.spyOn(bibliographyApi, 'get').mockRejectedValue(new Error('当前无权访问该资料'));
    render(<App><BibliographyPanel documentId="doc" versionId="version" /></App>);
    await screen.findByText('当前无权访问该资料');
    expect(screen.queryByText('A hydrogel study')).toBeNull();
    expect(screen.queryByText('核对书目信息')).toBeNull();
  });
  it('shows accepted metadata directly without review steps or historical workflow messages', async () => {
    vi.spyOn(bibliographyApi, 'get').mockResolvedValue({ ...value, status: 'ACCEPTED', message: '人工核对已保存' });
    render(<App><BibliographyPanel documentId="doc" versionId="version" /></App>);
    await screen.findByText('A hydrogel study');
    expect(screen.queryByText(/核对书目信息|填入候选信息|保存核对结果|人工核对已保存/)).toBeNull();
  });
  it('does not present an uncertain candidate as accepted or ask the customer to fill it', async () => {
    vi.spyOn(bibliographyApi, 'get').mockResolvedValue({ ...value, accepted: {}, candidates: [{ title: 'An uncertain match' }] });
    render(<App><BibliographyPanel documentId="doc" versionId="version" /></App>);
    await screen.findByText('部分书目信息暂未补全');
    expect(screen.queryByText('An uncertain match')).toBeNull();
    expect(screen.queryByText(/人工核对|填入候选信息/)).toBeNull();
  });
  it('keeps optional editing with revision checks and preserves unchanged structured fields', async () => {
    vi.spyOn(bibliographyApi, 'get').mockResolvedValue({ ...value, status: 'ACCEPTED' });
    const save = vi.spyOn(bibliographyApi, 'confirm').mockResolvedValue({ ...value, status: 'ACCEPTED', revision: 3 });
    render(<App><BibliographyPanel documentId="doc" versionId="version" canUpdate /></App>);
    fireEvent.click(screen.getByText('书目信息', { exact: true }));
    fireEvent.click(await screen.findByRole('button', { name: '编辑书目信息' }));
    fireEvent.change(await screen.findByLabelText('题名'), { target: { value: 'Corrected title' } });
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(save).toHaveBeenCalledWith('doc', 'version', 2, 'PAPER', { title: 'Corrected title' }));
    expect(screen.queryByText('填入候选信息')).toBeNull();
  });
});
