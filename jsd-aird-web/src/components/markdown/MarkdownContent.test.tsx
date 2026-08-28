import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { MarkdownContent } from './MarkdownContent';

describe('MarkdownContent images', () => {
  it('renders stable authenticated knowledge asset references as images', () => {
    render(<MarkdownContent value="对应图片：![产品结构](/api/v1/knowledge/assets/00000000-0000-0000-0000-000000000001/content)" />);

    expect(screen.getByRole('img', { name: '产品结构' })).toHaveAttribute(
      'src',
      '/api/v1/knowledge/assets/00000000-0000-0000-0000-000000000001/content',
    );
  });
});

describe('MarkdownContent math', () => {
  it('renders model-emphasized LaTeX as math and normalizes escaped subscripts', () => {
    const { container } = render(
      <MarkdownContent value={'交联剂：（*\\mathrm { C a C l } \\_ { 2 }*），浓度 *1 \\text{ mol L}^{-1}*，凝胶时间 *\\approx 10 \\text{ s}*。'} />,
    );

    const expressions = container.querySelectorAll('.ai-markdown-math');
    expect(expressions).toHaveLength(3);
    expect(expressions[0]).toHaveAttribute('data-latex', '\\mathrm{CaCl}_{ 2 }');
    expect(expressions[0]?.querySelector('.katex')).not.toBeNull();
    expect(expressions[0]?.querySelector('.katex-html')).toHaveTextContent('CaCl2');
  });

  it('keeps ordinary Markdown emphasis unchanged', () => {
    const { container } = render(<MarkdownContent value="这是*重要说明*。" />);

    expect(container.querySelector('em')).toHaveTextContent('重要说明');
    expect(container.querySelector('.ai-markdown-math')).toBeNull();
  });
});
