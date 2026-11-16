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
  it('displays arbitrary source fields for any document kind without empty paper fields', async () => {
    vi.spyOn(bibliographyApi, 'get').mockResolvedValue({ ...value, documentType: 'UNKNOWN',
      accepted: { title: '交付记录', document_kind: '交付记录', extra_fields: [{ label: '储存条件', value: '4°C' }, { label: '项目代号', value: 'AX-77' }] },
      provenance: { _model_extraction: { status: 'SUCCEEDED' } } });
    render(<App><BibliographyPanel documentId="doc" versionId="version" canUpdate /></App>);
    await screen.findByText('储存条件');
    expect(screen.getByText('4°C')).toBeTruthy();expect(screen.getByText('AX-77')).toBeTruthy();
    expect(screen.queryByText('DOI')).toBeNull();expect(screen.queryByText('会议日期')).toBeNull();
    fireEvent.click(screen.getByText('资料信息', { exact: true }));
    fireEvent.click(screen.getByRole('button', { name: '编辑资料信息' }));
    expect(await screen.findByRole('textbox', { name: '扩展信息名称 1' })).toHaveValue('储存条件');
    expect(screen.queryByLabelText('DOI')).toBeNull();
  });
  it('saves dynamic metadata edits with the existing revision and preserves standard fields', async () => {
    const record = { ...value, documentType: 'UNKNOWN', accepted: { title: '交付记录', extra_fields: [{ label: '项目代号', value: 'AX-77' }] } };
    vi.spyOn(bibliographyApi, 'get').mockResolvedValue(record);
    const save = vi.spyOn(bibliographyApi, 'confirm').mockResolvedValue({ ...record, revision: 3 });
    render(<App><BibliographyPanel documentId="doc" versionId="version" canUpdate /></App>);
    await screen.findByText('AX-77');fireEvent.click(screen.getByText('资料信息', { exact: true }));
    fireEvent.click(screen.getByRole('button', { name: '编辑资料信息' }));
    fireEvent.change(await screen.findByRole('textbox', { name: '扩展信息内容 1' }), { target: { value: 'AX-78' } });
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(save).toHaveBeenCalledWith('doc', 'version', 2, 'UNKNOWN', { extra_fields: [{ label: '项目代号', value: 'AX-78' }] }));
  });
  it('keeps pending extraction visible for unclassified materials', async () => {
    vi.spyOn(bibliographyApi, 'get').mockResolvedValue({ ...value, documentType: 'UNKNOWN', status: 'PENDING', accepted: {} });
    render(<App><BibliographyPanel documentId="doc" versionId="version" /></App>);
    await screen.findByText('正在补全资料信息');expect(screen.queryByText('仅显示已提取的信息；原文未提供的信息留空。')).toBeNull();
  });
});
