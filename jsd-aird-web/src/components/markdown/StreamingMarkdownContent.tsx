import { useCallback, useEffect, useRef, useState } from 'react';

import { MarkdownContent } from './MarkdownContent';

const MARKDOWN_REFRESH_MS = 33;

interface RenderedMarkdown {
  source: string;
  completedBlocks: string[];
  tail: string;
}

function splitMarkdown(value: string, final: boolean) {
  const lines = value.replace(/\r\n?/g, '\n').split('\n');
  const completedBlocks: string[] = [];
  let block: string[] = [];
  let codeFence = false;
  let mathFence: '$$' | '\\]' | undefined;

  const finishBlock = () => {
    const text = block.join('\n').trim();
    if (text) completedBlocks.push(text);
    block = [];
  };

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index] || '';
    const trimmed = line.trim();
    if (!codeFence && !mathFence && !trimmed) {
      let nextIndex = index + 1;
      while (nextIndex < lines.length && !(lines[nextIndex] || '').trim()) nextIndex += 1;
      const current = block.filter((item) => item.trim()).at(-1) || '';
      const next = lines[nextIndex] || '';
      const currentOrdered = /^\s*(\d+)[.)]\s+/.exec(current);
      const nextOrdered = /^\s*(\d+)[.)]\s+/.exec(next);
      const continuesOrderedList = Boolean(
        currentOrdered && nextOrdered && Number(nextOrdered[1]) === Number(currentOrdered[1]) + 1,
      );
      const continuesUnorderedList = /^\s*[-*+]\s+/.test(current) && /^\s*[-*+]\s+/.test(next);
      if (continuesOrderedList || continuesUnorderedList) {
        block.push('');
        continue;
      }
      finishBlock();
      index = nextIndex - 1;
      continue;
    }

    if (/^\s*```/.test(line)) codeFence = !codeFence;
    if (!codeFence) {
      if (!mathFence && /^\s*\$\$\s*$/.test(line)) mathFence = '$$';
      else if (!mathFence && /^\s*\\\[\s*$/.test(line)) mathFence = '\\]';
      else if (mathFence && trimmed === mathFence) mathFence = undefined;
    }
    block.push(line);
  }

  if (final) {
    finishBlock();
    return { completedBlocks, tail: '' };
  }
  return { completedBlocks, tail: block.join('\n') };
}

export function StreamingMarkdownContent({
  value,
  isStreaming = true,
  className = '',
}: {
  value: string;
  isStreaming?: boolean;
  className?: string;
}) {
  const [rendered, setRendered] = useState<RenderedMarkdown>({
    source: '',
    completedBlocks: [],
    tail: '',
  });
  const renderedRef = useRef(rendered);
  const latestValueRef = useRef(value);
  const timerRef = useRef<ReturnType<typeof setTimeout>>();
  const lastRenderAtRef = useRef(0);

  const flush = useCallback((final: boolean) => {
    const source = latestValueRef.current;
    const parts = splitMarkdown(source, final);
    const previous = renderedRef.current;
    const canAppend = source.startsWith(previous.source);
    const retainedBlocks = canAppend ? previous.completedBlocks : [];
    const retainedPrefixIsStable = retainedBlocks.every(
      (block, index) => parts.completedBlocks[index] === block,
    );
    const next = {
      source,
      completedBlocks: retainedPrefixIsStable
        ? [...retainedBlocks, ...parts.completedBlocks.slice(retainedBlocks.length)]
        : parts.completedBlocks,
      tail: parts.tail,
    };
    renderedRef.current = next;
    lastRenderAtRef.current = Date.now();
    setRendered(next);
  }, []);

  useEffect(() => {
    latestValueRef.current = value;
    if (!isStreaming) {
      if (timerRef.current !== undefined) clearTimeout(timerRef.current);
      timerRef.current = undefined;
      flush(true);
      return;
    }
    // Show the first arriving text immediately; coalesce later deltas to a
    // bounded 30 fps so Markdown stays responsive without reparsing per token.
    if (value && !renderedRef.current.source) {
      if (timerRef.current !== undefined) clearTimeout(timerRef.current);
      timerRef.current = undefined;
      flush(false);
      return;
    }
    if (timerRef.current !== undefined) return;
    const wait = Math.max(0, MARKDOWN_REFRESH_MS - (Date.now() - lastRenderAtRef.current));
    timerRef.current = setTimeout(() => {
      timerRef.current = undefined;
      flush(false);
    }, wait);
  }, [flush, isStreaming, value]);

  useEffect(
    () => () => {
      if (timerRef.current !== undefined) clearTimeout(timerRef.current);
    },
    [],
  );

  if (!value.trim() && !rendered.completedBlocks.length) return null;
  return (
    <div className={`streaming-markdown ${className}`.trim()}>
      {rendered.completedBlocks.map((block, index) => (
        <MarkdownContent key={`block-${index}`} value={block} />
      ))}
      {rendered.tail.trim() ? <MarkdownContent key="tail" value={rendered.tail} /> : null}
    </div>
  );
}
