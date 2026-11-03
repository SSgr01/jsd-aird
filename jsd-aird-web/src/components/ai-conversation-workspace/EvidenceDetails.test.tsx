import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import fixture from '../../../../jsd-aird-agent/contracts/evidence.json';
import type { SourceRef, SourceQuote } from '@/services/evidence/protocol';
import { httpClient } from '@/services/http/client';
import { AuthorizedEvidence, EvidenceDetails } from './EvidenceDetails';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
const source = fixture.cases[0]!.source as SourceRef;

describe('common source display', () => {
  it('shows a parent span and its full hash without claiming a complete parent was read', () => {
    const parent = fixture.cases.find((item) => 'parent_chunk_id' in item.source.anchor)!;
    render(<EvidenceDetails source={parent.source as SourceRef} excerpt={parent.text} />);
    expect(screen.getByText(/Unicode 码点/)).toBeTruthy();
    expect(screen.getByText(/父块片段预览/)).toBeTruthy();
    expect(screen.queryByLabelText('精确原文跨度')).toBeNull();
  });
  it('hides cached text until authorized and rechecks on reopening', async () => {
    const post = vi.spyOn(httpClient, 'post').mockResolvedValue({ data: { allowed: true } });
    const view = render(<AuthorizedEvidence source={source}>sensitive text</AuthorizedEvidence>);
    await screen.findByText('sensitive text');
    view.unmount();
    post.mockRejectedValueOnce(new Error('revoked'));
    render(<AuthorizedEvidence source={source}>sensitive text</AuthorizedEvidence>);
    expect(screen.queryByText('sensitive text')).toBeNull();
    await screen.findByText('来源暂不可访问，权限或资料版本可能已变化');
    expect(screen.queryByText('sensitive text')).toBeNull();
    expect(post).toHaveBeenCalledTimes(2);
  });

  it('does not show a previous authorization while a different source is loading', async () => {
    const post = vi.spyOn(httpClient, 'post').mockResolvedValueOnce({});
    const view = render(<AuthorizedEvidence source={source}>source A</AuthorizedEvidence>);
    await screen.findByText('source A');
    post.mockImplementationOnce(() => new Promise(() => {}));
    view.rerender(
      <AuthorizedEvidence source={{ ...source, version_id: 'another' }}>
        source B
      </AuthorizedEvidence>,
    );
    await waitFor(() => expect(post).toHaveBeenCalledTimes(2));
    expect(screen.queryByText('source A')).toBeNull();
    expect(screen.queryByText('source B')).toBeNull();
  });

  it('distinguishes node previews from exact quote handles', () => {
    const quote = { ...fixture.cases[0]!.quotes[0], source } as SourceQuote;
    const view = render(<EvidenceDetails source={source} excerpt="preview" />);
    expect(screen.getByText(/原文节点预览/)).toBeTruthy();
    expect(screen.queryByLabelText('精确原文跨度')).toBeNull();
    view.rerender(<EvidenceDetails source={source} excerpt="preview" quote={quote} />);
    expect(screen.getByLabelText('精确原文跨度')).toBeTruthy();
    expect(screen.queryByText('preview')).toBeNull();
  });
});
