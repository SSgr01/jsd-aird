import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { StructuredDocument } from '@/services/knowledge';
import { StructuredDocumentRenderer } from './StructuredDocumentRenderer';

describe('StructuredDocumentRenderer', () => {
  it('renders the material screenshot result as a semantic table without Excel axes', async () => {
    render(<StructuredDocumentRenderer value={materialDocument} />);

    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(screen.getAllByRole('row')).toHaveLength(3);
    expect(screen.getByText('材料基础信息').closest('th')).toHaveAttribute('colspan', '4');
    expect(screen.getByText('物料名称').closest('th')).toBeInTheDocument();
    expect(screen.getByText('TEST-TPL-丙烯酸树脂').closest('td')).toHaveAttribute('colspan', '3');
    expect(screen.queryByText('A')).not.toBeInTheDocument();
    expect(screen.queryByText('1')).not.toBeInTheDocument();
  });
});

const sourceNodeKeys = ['00000000-0000-0000-0000-000000000001'];

const materialDocument: StructuredDocument = {
  type: 'doc',
  schemaVersion: 1,
  content: [{
    type: 'table',
    content: [
      row('00000000-0000-0000-0000-000000000011', [cell('材料基础信息', true, 4)]),
      row('00000000-0000-0000-0000-000000000012', [
        cell('物料名称', true), cell('TEST-TPL-丙烯酸树脂', false, 3),
      ]),
      row('00000000-0000-0000-0000-000000000013', [
        cell('状态', true), cell('合格', false, 3),
      ]),
    ],
  }],
};

function row(reviewNodeId: string, content: NonNullable<StructuredDocument['content']>) {
  return { type: 'tableRow', attrs: { reviewNodeId, origin: 'source', sourceNodeKeys }, content };
}

function cell(text: string, header: boolean, colspan = 1) {
  return {
    type: header ? 'tableHeader' : 'tableCell',
    attrs: { colspan, rowspan: 1, colwidth: null },
    content: [{ type: 'paragraph', content: [{ type: 'text', text }] }],
  };
}
