import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import fixture from '../../../../jsd-aird-agent/contracts/evidence.json';
import type { SourceRef, SourceQuote } from '@/services/evidence/protocol';
import { httpClient } from '@/services/http/client';
import { AuthorizedEvidence, EvidenceDetails, EvidenceReader } from './EvidenceDetails';
import { knowledgeApi, type PublishedContent } from '@/services/knowledge/knowledge-api';

vi.mock('@/components/knowledge-document/OriginalDocumentViewer', () => ({
  OriginalDocumentViewer: (props: {
    selectedSourceNodeKey?: string;
    sourceNodes: Array<{ sourceNodeKey: string; sourceAnchor: { page?: number } }>;
  }) => (
    <div
      data-testid="original-file"
      data-node={props.selectedSourceNodeKey}
      data-page={
        props.sourceNodes.find((node) => node.sourceNodeKey === props.selectedSourceNodeKey)
          ?.sourceAnchor.page
      }
    />
  ),
}));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
const source = fixture.cases[0]!.source as SourceRef;

describe('common source display', () => {
  it('shows a related paragraph without exposing identifiers or claiming an exact quote', () => {
    const parent = fixture.cases.find((item) => 'parent_chunk_id' in item.source.anchor)!;
    render(<EvidenceDetails source={parent.source as SourceRef} excerpt={parent.text} />);
    expect(screen.getByText('相关段落')).toBeTruthy();
    expect(screen.queryByText(/Unicode|哈希|版本：/)).toBeNull();
    expect(screen.queryByText(parent.source.version_id)).toBeNull();
    expect(screen.queryByLabelText('引用原文')).toBeNull();
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
    expect(screen.getByText('相关段落')).toBeTruthy();
    expect(screen.queryByLabelText('引用原文')).toBeNull();
    view.rerender(<EvidenceDetails source={source} excerpt="preview" quote={quote} />);
    expect(screen.getByLabelText('引用原文')).toBeTruthy();
    expect(screen.queryByText('preview')).toBeNull();
  });

  it('locates a parent excerpt in its published original and never mounts the file after access is revoked', async () => {
    const parent = {
      ...source,
      kind: 'KB_PUBLICATION',
      publication_id: 'published',
      source_node_key: 'parent',
      anchor: { sourceNodeKeys: ['original-node'], page: 2 },
    } as SourceRef;
    const post = vi.spyOn(httpClient, 'post').mockResolvedValue({});
    vi.spyOn(knowledgeApi, 'publishedContent').mockResolvedValue({
      publication: { versionId: source.version_id },
      originalName: 'paper.pdf',
      contentType: 'application/pdf',
      sourceNodes: [{ sourceNodeKey: 'original-node', sourceAnchor: { page: 2 } }],
    } as unknown as PublishedContent);
    const view = render(
      <EvidenceReader
        evidence={{ title: '论文', source: parent, excerpt: '原文内容' }}
        onClose={vi.fn()}
      />,
    );
    const original = await screen.findByTestId('original-file');
    expect(original).toHaveAttribute('data-node', 'original-node');
    expect(original).toHaveAttribute('data-page', '2');
    view.unmount();
    post.mockRejectedValueOnce(new Error('revoked'));
    render(
      <EvidenceReader
        evidence={{ title: '论文', source: parent, excerpt: '原文内容' }}
        onClose={vi.fn()}
      />,
    );
    await screen.findByText('来源暂不可访问，权限或资料版本可能已变化');
    expect(screen.queryByTestId('original-file')).toBeNull();
    expect(screen.queryByText('原文内容')).toBeNull();
  });
});
