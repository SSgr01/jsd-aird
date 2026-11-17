import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { StructuredDocument } from '@/services/knowledge';
import { DocumentParts } from './DocumentParts';
import { partDocument, replaceDocumentPart } from './document-parts';

const value: StructuredDocument = { type: 'doc', schemaVersion: 1, content: [
  { type: 'documentPart', attrs: { partNo: 1 }, content: [{ type: 'paragraph', attrs: { sourceNodeKeys: ['source-a'] }, content: [{ type: 'text', text: 'first' }] }] },
  { type: 'documentPart', attrs: { partNo: 2 }, content: [{ type: 'paragraph', attrs: { sourceNodeKeys: ['source-b'] }, content: [{ type: 'text', text: 'last' }] }] },
] };

describe('document parts', () => {
  it('renders one part at a time and navigates without mounting the whole book', () => {
    render(<DocumentParts value={value}>{(page) => <p>{page.content?.[0]?.content?.[0]?.text}</p>}</DocumentParts>);
    expect(screen.getByText('first')).toBeInTheDocument();
    expect(screen.queryByText('last')).toBeNull();
    fireEvent.click(screen.getByTitle('2'));
    expect(screen.getByText('last')).toBeInTheDocument();
    expect(screen.queryByText('first')).toBeNull();
  });

  it('locates a source in another part', async () => {
    render(<DocumentParts value={value} selectedSourceNodeKey="source-b">{(page) => <p>{page.content?.[0]?.content?.[0]?.text}</p>}</DocumentParts>);
    await waitFor(() => expect(screen.getByText('last')).toBeInTheDocument());
  });

  it('merges an edited part while preserving every other part and its provenance', () => {
    const edited: StructuredDocument = { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'edited' }] }] };
    const saved = replaceDocumentPart(value, 0, edited);
    expect(saved.content?.[1]).toBe(value.content?.[1]);
    expect(saved.content?.[0]?.attrs).toEqual({ partNo: 1 });
    expect(partDocument(saved, 0).content).toEqual(edited.content);
    expect(partDocument(value, 0).content?.[0]?.content?.[0]?.text).toBe('first');
  });
});
