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

describe('MarkdownContent escaped inline text', () => {
  it('shows backend-escaped file names literally without triggering emphasis', () => {
    const { container } = render(
      <MarkdownContent value={'应用测试报告&#95;Synthetic&#95;10&#95;T07B.xlsx 与 A&amp;B'} />,
    );

    expect(container).toHaveTextContent('应用测试报告_Synthetic_10_T07B.xlsx 与 A&B');
    expect(container.querySelector('em')).toBeNull();
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

describe('MarkdownContent ordered lists', () => {
  it('keeps sequential items in one list across blank lines', () => {
    const { container } = render(<MarkdownContent value={'1. 第一项\n\n2. 第二项\n\n3) 第三项'} />);

    const lists = container.querySelectorAll('ol');
    expect(lists).toHaveLength(1);
    expect(lists[0]).toHaveAttribute('start', '1');
    expect(Array.from(lists[0]?.querySelectorAll('li') || []).map((item) => item.getAttribute('value')))
      .toEqual(['1', '2', '3']);
    expect(lists[0]).toHaveTextContent('第一项');
    expect(lists[0]).toHaveTextContent('第二项');
    expect(lists[0]).toHaveTextContent('第三项');
  });

  it('keeps an explicit restart as a separate list', () => {
    const { container } = render(<MarkdownContent value={'1. 第一项\n\n1. 新列表'} />);

    const lists = container.querySelectorAll('ol');
    expect(lists).toHaveLength(2);
    expect(lists[0]).toHaveAttribute('start', '1');
    expect(lists[1]).toHaveAttribute('start', '1');
  });

  it('preserves a non-one source starting number', () => {
    const { container } = render(<MarkdownContent value={'4. 第四项\n\n5. 第五项'} />);

    const list = container.querySelector('ol');
    expect(list).not.toBeNull();
    expect(list).toHaveAttribute('start', '4');
    expect(Array.from(list?.querySelectorAll('li') || []).map((item) => item.getAttribute('value')))
      .toEqual(['4', '5']);
  });

  it('ends a list before a normal paragraph or heading', () => {
    const { container } = render(<MarkdownContent value={'1. 第一项\n\n说明文字\n\n# 新标题\n\n2. 第二列表'} />);

    expect(container.querySelectorAll('ol')).toHaveLength(2);
    expect(container.querySelector('p')).toHaveTextContent('说明文字');
    expect(container.querySelector('h1')).toHaveTextContent('新标题');
  });
});
