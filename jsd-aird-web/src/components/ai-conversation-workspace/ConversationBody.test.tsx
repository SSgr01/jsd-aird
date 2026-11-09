import { act, render, screen } from '@testing-library/react';
import { ConversationBody } from './ConversationBody';
import { cleanConversationText } from './conversation-text';

afterEach(() => vi.useRealTimers());

it('hides model status metadata and internal identifiers while preserving conclusions and citations', () => {
  const text =
    '**任务状态**：`阶段性结果` (部分完成)\n\n凝胶含水约 81%。[来源 1](#research-evidence-e22d5676261ab3e33107f180b)\n（证据 ID: `e22d5676261ab3e33107f180b`） &#x20;\n报告仍缺少 CSV，下一步需要补齐。';
  const { container } = render(<ConversationBody text={text} />);
  expect(screen.queryByText(/任务状态/)).not.toBeInTheDocument();
  expect(container.textContent).not.toMatch(/证据 ID|e22d5676261ab3e33107f180b|&#x20;/);
  expect(screen.getByRole('link', { name: '来源 1' })).toHaveAttribute(
    'href',
    '#research-evidence-e22d5676261ab3e33107f180b',
  );
  expect(screen.getByText(/含水约 81%/)).toBeInTheDocument();
  expect(screen.getByText(/报告仍缺少 CSV/)).toBeInTheDocument();
});

it('keeps scientific units, code examples and explanatory status sentences', () => {
  const code =
    '```text\n**任务状态**：`PARTIAL`\n证据 ID: `e22d5676261ab3e33107f180b`\n\n\n&#x20;\n```';
  const result = cleanConversationText(
    '频率为 `1 Hz`。&nbsp;质量为 `0.81 kg`。\n任务状态尚不确定，请不要重复提交。\n' + code,
  );
  expect(result).toContain('频率为 `1 Hz`。 质量为 `0.81 kg`。');
  expect(result).toContain('任务状态尚不确定，请不要重复提交。');
  expect(result).toContain(code);
  expect(cleanConversationText('原文支持这一结论。[E:e22d5676261ab3e33107f180b]')).toContain(
    '[E:e22d5676261ab3e33107f180b]',
  );
});

it('keeps paragraphs and citation links mounted as text grows and streaming completes', () => {
  vi.useFakeTimers();
  const first = '含水约 81%。[来源 1](#research-evidence-e123)';
  const { rerender } = render(<ConversationBody text={first} streaming />);
  const paragraph = screen.getByText(/含水约/);
  const link = screen.getByRole('link', { name: '来源 1' });
  const text = first + '\n\n以 1 kg 凝胶计算，水约为 0.81 kg。';
  rerender(<ConversationBody text={text} streaming />);
  act(() => {
    vi.advanceTimersByTime(34);
  });
  expect(screen.getByText(/含水约/)).toBe(paragraph);
  expect(screen.getByRole('link', { name: '来源 1' })).toBe(link);
  const last = screen.getByText(/水约为 0.81/);
  rerender(<ConversationBody text={text} />);
  expect(screen.getByText(/含水约/)).toBe(paragraph);
  expect(screen.getByText(/水约为 0.81/)).toBe(last);
});

it.each([
  ['table', '| 条件 | 数值 |\n| --- | --- |\n| 温度 | 25 °C |', '\n| 时间 | 30 min |', 'table'],
  ['list', '1. 核对温度', '\n\n2. 核对时间', 'ol'],
  ['code', '```json\n{', '\n"water": 0.81\n}\n```', 'pre'],
])(
  'keeps a growing %s in place and flushes the final characters',
  (_name, initial, addition, selector) => {
    vi.useFakeTimers();
    const { container, rerender } = render(<ConversationBody text={initial} streaming />);
    const element = container.querySelector(selector);
    expect(element).not.toBeNull();
    rerender(<ConversationBody text={initial + addition} streaming />);
    act(() => {
      vi.advanceTimersByTime(34);
    });
    expect(container.querySelector(selector)).toBe(element);
    rerender(<ConversationBody text={initial + addition + '\n\n核对完成。'} />);
    expect(container.querySelector(selector)).toBe(element);
    expect(screen.getByText('核对完成。')).toBeVisible();
  },
);
