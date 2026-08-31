import { render, screen, waitFor } from '@testing-library/react';
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

  it('renders charts through the stable authenticated asset endpoint', async () => {
    render(<StructuredDocumentRenderer value={visualDocument('CHART', 'asset-chart', 'Figure 3 电荷密度')} />);

    const chart = await screen.findByRole('img', { name: 'Figure 3 电荷密度' });
    expect(chart).toHaveAttribute('src', '/api/v1/knowledge/assets/asset-chart/content');
    expect(chart.closest('figure')).toHaveAttribute('data-visual-type', 'CHART');
  });

  it('uses the visual semantic type when an asset is unavailable', async () => {
    render(<StructuredDocumentRenderer value={visualDocument('CHART')} />);

    expect(await screen.findByText('图表')).toBeInTheDocument();
    expect(screen.queryByText('图片')).not.toBeInTheDocument();
  });

  it('renders raw MinerU latex with KaTeX without overwriting the source value', async () => {
    const latexRaw = '3 . 5 \\times 1 0 ^ { - 1 2 } \\mathrm { S } \\mathrm { c m } ^ { - 1 }';
    const { container } = render(<StructuredDocumentRenderer value={{
      type: 'doc', schemaVersion: 1,
      content: [{
        type: 'paragraph', attrs: { reviewNodeId: '00000000-0000-0000-0000-000000000021', origin: 'source', sourceNodeKeys },
        content: [
          { type: 'text', text: 'Conductivity: ' },
          { type: 'inlineMath', attrs: { latexRaw } },
          { type: 'text', text: '10', marks: [{ type: 'superscript' }] },
        ],
      }, {
        type: 'formula', attrs: {
          reviewNodeId: '00000000-0000-0000-0000-000000000022', origin: 'source', sourceNodeKeys,
          latexRaw: '\\frac{\\partial^2 C}{\\partial x^2}',
        },
      }],
    }} />);

    await waitFor(() => expect(container.querySelectorAll('.katex')).toHaveLength(2));
    const inlineMath = container.querySelector<HTMLElement>('[data-inline-math]');
    expect(inlineMath).toHaveAttribute('data-latex-raw', latexRaw);
    expect(inlineMath?.dataset.renderLatex).toContain('10^{');
    expect(inlineMath?.dataset.renderLatex).toContain('\\mathrm{cm}');
    expect(container.querySelector('sup')).toHaveTextContent('10');
    expect(container.querySelector('[data-formula] .katex')).toBeInTheDocument();
  });

  it('keeps rendering historical formula nodes whose latex is stored as text content', async () => {
    const { container } = render(<StructuredDocumentRenderer value={{
      type: 'doc', schemaVersion: 1,
      content: [{ type: 'formula', content: [{ type: 'text', text: 'x^2' }] }],
    }} />);

    await waitFor(() => expect(container.querySelector('[data-formula] .katex')).toBeInTheDocument());
    expect(container.querySelector('[data-formula]')).toHaveAttribute('data-latex-raw', 'x^2');
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

function visualDocument(visualType: 'IMAGE' | 'CHART', assetFileId?: string, caption = ''): StructuredDocument {
  return {
    type: 'doc', schemaVersion: 1,
    content: [{
      type: 'image',
      attrs: { origin: 'source', sourceNodeKeys, visualType, assetFileId, caption },
      content: caption ? [{ type: 'text', text: caption }] : [],
    }],
  };
}
