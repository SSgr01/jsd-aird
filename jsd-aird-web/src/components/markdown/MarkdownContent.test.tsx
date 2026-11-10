import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { MarkdownContent } from './MarkdownContent';

describe('chemistry Markdown', () => {
  it('renders mhchem reactions and ordinary mathematics', () => {
    const { container } = render(<MarkdownContent value={'$$\\ce{CH2=CH2 + H2 ->[Ni] CH3CH3}$$\n\n$x^2$'} />);
    expect(container.querySelectorAll('.katex')).toHaveLength(2);
    expect(container.querySelector('.katex-error')).toBeNull();
  });
  it('draws a completed SMILES fence without putting structure data in HTTP queries', () => {
    render(<MarkdownContent value={'```smiles\nc1ccccc1\n```'} />);
    const frame = screen.getByTitle('分子结构');
    expect(frame.getAttribute('src')).toBe('/chemistry/viewer.html#source=c1ccccc1&kind=smiles&format=raw');
  });
  it('keeps incomplete streamed structures as text', () => {
    const { container } = render(<MarkdownContent value={'```smiles\nc1ccc'} />);
    expect(container.querySelector('iframe')).toBeNull();
    expect(container).toHaveTextContent('c1ccc');
  });
  it('renders a complete legacy named molecule list and preserves review metadata', () => {
    const { container } = render(<MarkdownContent value={'```yaml review="paper-1"\nS2: CCO\nS3: OCC\n```'} />);
    const frame = container.querySelector('iframe');
    const params = new URLSearchParams(frame!.getAttribute('src')!.split('#')[1]);
    expect(params.get('source')).toBe('S2: CCO\nS3: OCC');
    expect(params.get('review')).toBe('paper-1');
    expect(params.get('kind')).toBe('smiles');
  });
  it('keeps ordinary YAML and incomplete legacy molecule lists as code', () => {
    const { container } = render(<MarkdownContent value={'```yaml\nS2: enabled\nother: true\n```\n\n```yaml\nS1: CCO\nS2: OCC'} />);
    expect(container.querySelector('iframe')).toBeNull();
    expect(container.querySelectorAll('pre')).toHaveLength(2);
  });
  it('accepts a supplied name without trusting a model verification flag', () => {
    render(<MarkdownContent value={'```smiles name="乙醇" verified="true"\nCCO\n```'} />);
    const params = new URLSearchParams(screen.getByTitle('分子结构').getAttribute('src')!.split('#')[1]);
    expect(params.get('name')).toBe('乙醇');
    expect(params.has('verified')).toBe(false);
  });
  it('supports reaction SMILES', () => {
    render(<MarkdownContent value={'```reaction-smiles\nCCO>>CC=O\n```'} />);
    expect(screen.getByTitle('化学反应结构')).toHaveAttribute('src', expect.stringContaining('kind=reaction-smiles'));
  });
  it('accepts generic named lists and routes long explicit structures to the viewer budget check', () => {
    const { container } = render(<MarkdownContent value={'```yaml\nA: CCO\nBDB: c1ccccc1\nESBO: CC1CO1\n```\n\n```smiles\n' + 'C'.repeat(5000) + '\n```'} />);
    const frames = container.querySelectorAll('iframe');
    expect(frames).toHaveLength(2);
    const params = new URLSearchParams(frames[0]!.getAttribute('src')!.split('#')[1]);
    expect(params.get('source')).toBe('A: CCO\nBDB: c1ccccc1\nESBO: CC1CO1');
  });
  it('uses one label grammar for chemical symbols, punctuation and long supplied names', () => {
    const label = '超长样品名称'.repeat(30);
    const values = ['A:CCO', 'B:CCO', '样品：CCO', '[BDB]: CCO', label + ':CCO'];
    const { container } = render(<MarkdownContent value={values.map(value => '```yaml\n' + value + '\n```').join('\n\n')} />);
    const frames = container.querySelectorAll('iframe');
    expect(frames).toHaveLength(values.length);
    frames.forEach((frame, index) => {
      const params = new URLSearchParams(frame.getAttribute('src')!.split('#')[1]);
      expect(params.get('source')).toBe(values[index]);
      expect(params.get('format')).toBe('labels');
    });
    const named = render(<MarkdownContent value={'```smiles name="' + label + '"\nCCO\n```'} />);
    const params = new URLSearchParams(named.container.querySelector('iframe')!.getAttribute('src')!.split('#')[1]);
    expect(params.get('name')).toBe(label);
  });
});

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
