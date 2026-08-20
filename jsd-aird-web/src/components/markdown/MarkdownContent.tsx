import type { ReactNode } from 'react';

interface MarkdownContentProps {
  value?: string | null;
  className?: string;
}

function parseInline(value: string, keyPrefix: string): ReactNode[] {
  const tokenPattern = /(\*\*[^*\n]+\*\*|__[^_\n]+__|`[^`\n]+`|\[[^\]\n]+\]\([^\n)]+\)|\*[^*\n]+\*|_[^_\n]+_|\$[^$\n]+\$)/g;
  const nodes: ReactNode[] = [];
  let cursor = 0;
  let match: RegExpExecArray | null;
  let tokenIndex = 0;

  while ((match = tokenPattern.exec(value))) {
    if (match.index > cursor) nodes.push(value.slice(cursor, match.index));
    const token = match[0];
    const key = `${keyPrefix}-${tokenIndex}`;
    tokenIndex += 1;

    if (token.startsWith('**') || token.startsWith('__')) {
      nodes.push(<strong key={key}>{parseInline(token.slice(2, -2), `${key}-strong`)}</strong>);
    } else if (token.startsWith('`')) {
      nodes.push(<code key={key}>{token.slice(1, -1)}</code>);
    } else if (token.startsWith('[')) {
      const link = /^\[([^\]]+)\]\(([^)]+)\)$/.exec(token);
      const href = link?.[2]?.trim();
      if (!link || !href || !/^(https?:\/\/|\/|#)/i.test(href)) {
        nodes.push(token);
      } else {
        nodes.push(<a key={key} href={href} target={href.startsWith('http') ? '_blank' : undefined} rel={href.startsWith('http') ? 'noreferrer' : undefined}>{parseInline(link[1] || '', `${key}-link`)}</a>);
      }
    } else if (token.startsWith('$')) {
      nodes.push(<span key={key} className="ai-markdown-math">{token.slice(1, -1)}</span>);
    } else {
      nodes.push(<em key={key}>{parseInline(token.slice(1, -1), `${key}-emphasis`)}</em>);
    }
    cursor = match.index + token.length;
  }

  if (cursor < value.length) nodes.push(value.slice(cursor));
  return nodes;
}

function renderParagraph(lines: string[], key: string) {
  return <p key={key}>{lines.flatMap((line, index) => [
    ...(index ? [<br key={`${key}-break-${index}`} />] : []),
    ...parseInline(line, `${key}-line-${index}`),
  ])}</p>;
}

function tableCells(line: string) {
  const value = line.trim().replace(/^\|/, '').replace(/\|$/, '');
  return value.split('|').map((cell) => cell.trim());
}

function isTableSeparator(line: string) {
  return line.includes('|') && tableCells(line).length > 0 && tableCells(line).every((cell) => /^:?-{3,}:?$/.test(cell));
}

function isBlockStart(line: string, nextLine?: string) {
  return /^\s*(#{1,6})\s+/.test(line)
    || /^\s*```/.test(line)
    || /^\s*[-*+]\s+/.test(line)
    || /^\s*\d+[.)]\s+/.test(line)
    || /^\s*>/.test(line)
    || /^\s*([-*_])(?:\s*\1){2,}\s*$/.test(line)
    || (line.includes('|') && Boolean(nextLine) && isTableSeparator(nextLine as string))
    || /^\s*(?:\{|\[)/.test(line);
}

function JsonBlock({ value, className }: { value: unknown; className: string }) {
  return <pre key={className} className="ai-markdown-code"><code>{JSON.stringify(value, null, 2)}</code></pre>;
}

function renderBlocks(value: string): ReactNode[] {
  const lines = value.replace(/\r\n?/g, '\n').split('\n');
  const blocks: ReactNode[] = [];
  let index = 0;

  while (index < lines.length) {
    const line = lines[index] ?? '';
    if (!line.trim()) {
      index += 1;
      continue;
    }

    const fence = /^\s*```\s*([\w-]+)?\s*$/.exec(line);
    if (fence) {
      const codeLines: string[] = [];
      index += 1;
      while (index < lines.length && !/^\s*```\s*$/.test(lines[index] ?? '')) {
        codeLines.push(lines[index] ?? '');
        index += 1;
      }
      if (index < lines.length) index += 1;
      blocks.push(<pre key={`code-${index}`} className="ai-markdown-code"><code data-language={fence[1] || undefined}>{codeLines.join('\n')}</code></pre>);
      continue;
    }

    const heading = /^\s*(#{1,6})\s+(.+?)\s*$/.exec(line);
    if (heading) {
      const level = heading[1] || '';
      const text = parseInline(heading[2] || '', `heading-${index}`);
      if (level.length === 1) blocks.push(<h1 key={`heading-${index}`}>{text}</h1>);
      else if (level.length === 2) blocks.push(<h2 key={`heading-${index}`}>{text}</h2>);
      else if (level.length === 3) blocks.push(<h3 key={`heading-${index}`}>{text}</h3>);
      else if (level.length === 4) blocks.push(<h4 key={`heading-${index}`}>{text}</h4>);
      else if (level.length === 5) blocks.push(<h5 key={`heading-${index}`}>{text}</h5>);
      else blocks.push(<h6 key={`heading-${index}`}>{text}</h6>);
      index += 1;
      continue;
    }

    if (line.includes('|') && index + 1 < lines.length && isTableSeparator(lines[index + 1] ?? '')) {
      const headers = tableCells(line);
      const rows: string[][] = [];
      index += 2;
      while (index < lines.length && (lines[index] ?? '').includes('|') && (lines[index] ?? '').trim()) {
        rows.push(tableCells(lines[index] ?? ''));
        index += 1;
      }
      blocks.push(<div key={`table-${index}`} className="ai-markdown-table-wrap"><table className="ai-markdown-table"><thead><tr>{headers.map((cell, cellIndex) => <th key={`table-head-${cellIndex}`}>{parseInline(cell, `table-head-${index}-${cellIndex}`)}</th>)}</tr></thead><tbody>{rows.map((row, rowIndex) => <tr key={`table-row-${rowIndex}`}>{headers.map((_, cellIndex) => <td key={`table-cell-${rowIndex}-${cellIndex}`}>{parseInline(row[cellIndex] || '', `table-cell-${index}-${rowIndex}-${cellIndex}`)}</td>)}</tr>)}</tbody></table></div>);
      continue;
    }

    const unordered = /^\s*[-*+]\s+(.+)$/.exec(line);
    if (unordered) {
      const items: string[] = [];
      while (index < lines.length) {
        const item = /^\s*[-*+]\s+(.+)$/.exec(lines[index] ?? '');
        if (!item) break;
        items.push(item[1] || '');
        index += 1;
      }
      blocks.push(<ul key={`unordered-${index}`}>{items.map((item, itemIndex) => <li key={`unordered-${index}-${itemIndex}`}>{parseInline(item, `unordered-${index}-${itemIndex}`)}</li>)}</ul>);
      continue;
    }

    const ordered = /^\s*\d+[.)]\s+(.+)$/.exec(line);
    if (ordered) {
      const items: string[] = [];
      while (index < lines.length) {
        const item = /^\s*\d+[.)]\s+(.+)$/.exec(lines[index] ?? '');
        if (!item) break;
        items.push(item[1] || '');
        index += 1;
      }
      blocks.push(<ol key={`ordered-${index}`}>{items.map((item, itemIndex) => <li key={`ordered-${index}-${itemIndex}`}>{parseInline(item, `ordered-${index}-${itemIndex}`)}</li>)}</ol>);
      continue;
    }

    const quote = /^\s*>\s?(.*)$/.exec(line);
    if (quote) {
      const quoteLines: string[] = [];
      while (index < lines.length) {
        const item = /^\s*>\s?(.*)$/.exec(lines[index] ?? '');
        if (!item) break;
        quoteLines.push(item[1] || '');
        index += 1;
      }
      blocks.push(<blockquote key={`quote-${index}`}>{renderParagraph(quoteLines, `quote-${index}`)}</blockquote>);
      continue;
    }

    if (/^\s*([-*_])(?:\s*\1){2,}\s*$/.test(line)) {
      blocks.push(<hr key={`rule-${index}`} />);
      index += 1;
      continue;
    }

    if (/^\s*(?:\{|\[)/.test(line)) {
      let jsonRendered = false;
      for (let end = Math.min(lines.length, index + 160); end > index; end -= 1) {
        try {
          const parsed = JSON.parse(lines.slice(index, end).join('\n')) as unknown;
          blocks.push(<JsonBlock key={`json-${index}`} value={parsed} className={`json-${index}`} />);
          index = end;
          jsonRendered = true;
          break;
        } catch {
          if (end === index + 1) break;
        }
      }
      if (jsonRendered) continue;
    }

    const paragraphLines: string[] = [line];
    index += 1;
    while (index < lines.length && (lines[index] ?? '').trim() && !isBlockStart(lines[index] ?? '', lines[index + 1])) {
      paragraphLines.push(lines[index] ?? '');
      index += 1;
    }
    blocks.push(renderParagraph(paragraphLines, `paragraph-${index}`));
  }

  return blocks;
}

export function MarkdownContent({ value, className = '' }: MarkdownContentProps) {
  if (!value?.trim()) return null;
  return <div className={`ai-markdown ${className}`.trim()}>{renderBlocks(value)}</div>;
}
