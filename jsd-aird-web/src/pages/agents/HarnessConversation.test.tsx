import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { HarnessConversation } from './HarnessConversation';
import type { AgentRun, Conversation } from '@/services/agents/agent-api';
vi.mock('./ResearchOperations', () => ({ ResearchOperations: () => null }));

afterEach(cleanup);
const base = {
  draft: '',
  request: '',
  busy: false,
  ready: true,
  restoring: false,
  progress: [],
  onRequest: vi.fn(),
  onRestoreQueued: vi.fn(),
  onSend: vi.fn(),
  onCancel: vi.fn(),
  onRetry: vi.fn(),
  onQueue: vi.fn(),
  onApproval: vi.fn(),
  onMaterials: vi.fn(),
  onCitation: vi.fn(),
};
const run = {
  id: 'run',
  status: 'RUNNING',
  evidence: {},
  assistant_messages: [
    { id: 'reading', text: '我找到了论文中含水量的数据，接下来计算每千克中的水质量。' },
  ],
  plan: {
    task_type: 'RESEARCH',
    steps: [{ id: 'read', title: '阅读文献', status: 'in_progress' }],
    deliverables: ['report'],
  },
} as unknown as AgentRun;

it('describes full text approval without exposing a paper identifier or a blank search', () => {
  render(
    <HarnessConversation
      {...base}
      run={{
        ...run,
        status: 'WAITING_APPROVAL',
        pending_approval: {
          id: 'approval',
          tool: 'literature_acquire',
          arguments: { work_id: 'W123' },
          expires_at: Date.now() / 1000 + 900,
          description: '获取已选论文的开放全文，解析后用于本次研究。不会发布到企业知识库。',
        },
      }}
    />,
  );
  expect(screen.getByText('允许获取论文全文吗？')).toBeVisible();
  expect(screen.getByText(/获取已选论文的开放全文/)).toBeVisible();
  expect(screen.queryByText(/W123|将搜索：/)).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '允许获取全文' }));
  expect(base.onApproval).toHaveBeenCalledWith('APPROVED');
});

it('keeps assistant speech, the streaming answer and plan while hiding execution logs', () => {
  render(
    <HarnessConversation
      {...base}
      run={run}
      draft="按原文计算，水约为 0.81 kg。"
      progress={[{ key: 1, text: '已执行隔离数据分析' }]}
    />,
  );
  expect(screen.getByText(/我找到了论文中含水量/)).toBeInTheDocument();
  expect(screen.getByText(/水约为 0.81 kg/)).toBeInTheDocument();
  expect(screen.getByLabelText('研究计划')).toBeInTheDocument();
  expect(screen.queryByText('已执行隔离数据分析')).not.toBeInTheDocument();
  expect(screen.queryByText('查看活动记录')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '停止当前任务' }));
  expect(base.onCancel).toHaveBeenCalled();
});

it('retains partial speech and real downloads after stop, without inventing a report', () => {
  render(
    <HarnessConversation
      {...base}
      run={{
        ...run,
        status: 'CANCELLED',
        registered_outputs: [{ id: 'file', kind: 'artifact', title: 'result.json' }],
      }}
    />,
  );
  expect(screen.getByText(/我找到了论文中含水量/)).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /下\s*载/ })).toHaveAttribute(
    'href',
    expect.stringContaining('/file/content'),
  );
  expect(screen.queryByRole('button', { name: '阅读报告' })).not.toBeInTheDocument();
  expect(screen.getByText(/报告还没完成/)).toBeInTheDocument();
  expect(screen.getByLabelText('研究计划')).toHaveAttribute('open');
  expect(screen.queryByText(/暂时无法检查报告/)).not.toBeInTheDocument();
});

it('blocks click and keyboard resubmission while an operation result is unknown', () => {
  render(
    <HarnessConversation
      {...base}
      request="继续"
      run={{ ...run, status: 'FAILED', error: 'TOOL_RESULT_UNKNOWN', retryable: true }}
    />,
  );
  expect(screen.getByRole('button', { name: '发送' })).toBeDisabled();
  expect(screen.queryByRole('button', { name: '继续处理' })).not.toBeInTheDocument();
  fireEvent.keyDown(screen.getByRole('textbox', { name: '交代任务或继续交流' }), { key: 'Enter' });
  expect(base.onSend).not.toHaveBeenCalled();
});

it('names queued sending clearly and keeps stop available while drafting', () => {
  base.onSend.mockClear();
  base.onCancel.mockClear();
  render(<HarnessConversation {...base} run={run} request="补充温度和时间" />);
  fireEvent.click(screen.getByRole('button', { name: '排队发送' }));
  expect(base.onSend).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: '停止当前任务' }));
  expect(base.onCancel).toHaveBeenCalledTimes(1);
});

it('distinguishes steering waiting for acceptance from requirements already received', () => {
  const conversation = {
    id: 'conversation',
    revision: 1,
    messages: [
      { id: 'steer', role: 'user', text: '请补充温度和时间', status: 'PENDING', run_id: run.id },
    ],
  } as Conversation;
  const { rerender } = render(
    <HarnessConversation {...base} run={run} conversation={conversation} />,
  );
  expect(screen.getByText('等待当前步骤结束后接收')).toBeVisible();
  rerender(
    <HarnessConversation
      {...base}
      run={run}
      conversation={{
        ...conversation,
        messages: conversation.messages.map((item) => ({ ...item, status: 'DELIVERED' })),
      }}
    />,
  );
  expect(screen.queryByText('等待当前步骤结束后接收')).not.toBeInTheDocument();
  expect(screen.getByText('已加入当前任务')).toBeVisible();
});

it('focuses the main composer for editing after the restore request finishes', async () => {
  const onRestoreQueued = vi.fn().mockResolvedValue(true);
  const conversation: Conversation = {
    id: 'conversation',
    revision: 1,
    messages: [],
    queued: [{ id: 'queued', role: 'user', text: '请补充温度和时间' }],
  };
  const props = { ...base, run, conversation, onRestoreQueued };
  const { rerender } = render(<HarnessConversation {...props} />);
  fireEvent.click(screen.getByRole('button', { name: '编辑消息：请补充温度和时间' }));
  rerender(<HarnessConversation {...props} busy />);
  const input = screen.getByRole('textbox', { name: '交代任务或继续交流' });
  await waitFor(() => expect(onRestoreQueued).toHaveBeenCalledTimes(1));
  expect(input).toBeDisabled();
  expect(input).not.toHaveFocus();
  rerender(
    <HarnessConversation
      {...props}
      request="请补充温度和时间"
      conversation={{ ...conversation, queued: [] }}
    />,
  );
  await waitFor(() => expect(input).toHaveFocus());
  expect(screen.queryByRole('region', { name: '待处理消息' })).not.toBeInTheDocument();
});

it('keeps the same reply and plan when the first and following streamed messages complete', () => {
  const initial = { ...run, assistant_messages: [] };
  const { rerender } = render(
    <HarnessConversation {...base} run={initial} draftId="first" draft="两组实验的温度不同。" />,
  );
  const first = screen.getByText('两组实验的温度不同。');
  const plan = screen.getByLabelText('研究计划');
  const firstComplete = {
    ...initial,
    assistant_messages: [{ id: 'first', text: '两组实验的温度不同。' }],
  };
  rerender(<HarnessConversation {...base} run={firstComplete} />);
  expect(screen.getByText('两组实验的温度不同。')).toBe(first);
  expect(screen.getByLabelText('研究计划')).toBe(plan);
  rerender(
    <HarnessConversation
      {...base}
      run={firstComplete}
      draftId="second"
      draft="目前不能直接比较强度。"
    />,
  );
  const second = screen.getByText('目前不能直接比较强度。');
  rerender(
    <HarnessConversation
      {...base}
      run={{
        ...firstComplete,
        assistant_messages: [
          ...firstComplete.assistant_messages,
          { id: 'second', text: '目前不能直接比较强度。' },
        ],
      }}
    />,
  );
  expect(screen.getByText('两组实验的温度不同。')).toBe(first);
  expect(screen.getByText('目前不能直接比较强度。')).toBe(second);
  expect(screen.getByLabelText('研究计划')).toBe(plan);
});

it('retains the received partial text when the user stops before message completion', () => {
  const { rerender } = render(
    <HarnessConversation
      {...base}
      run={run}
      draftId="partial"
      draft="已核对两组实验的温度，时间条件还"
    />,
  );
  const text = screen.getByText('已核对两组实验的温度，时间条件还');
  rerender(
    <HarnessConversation
      {...base}
      run={{ ...run, status: 'CANCELLED' }}
      draftId="partial"
      draft="已核对两组实验的温度，时间条件还"
    />,
  );
  expect(screen.getByText('已核对两组实验的温度，时间条件还')).toBe(text);
});

it('reconciles a completed snapshot arriving before its final streamed event', () => {
  render(
    <HarnessConversation
      {...base}
      run={{ ...run, assistant_messages: [{ id: 'reply', text: '两组实验的温度不同。' }] }}
      draftId="reply"
      draft="两组实验的温度"
    />,
  );
  expect(screen.getByText('两组实验的温度不同。')).toBeVisible();
  expect(screen.queryByText('两组实验的温度', { exact: true })).not.toBeInTheDocument();
});

it('places replies after their accepted supplement, including a streaming reply', () => {
  const supplements = [1, 2].map((seq) => ({
    id: `supplement-${seq}`,
    role: 'user',
    text: `补充要求${seq}`,
    seq,
    status: 'DELIVERED' as const,
    run_id: run.id,
  }));
  const { container } = render(
    <HarnessConversation
      {...base}
      run={{
        ...run,
        request: '最初的问题',
        messages: supplements,
        assistant_messages: [
          { id: 'answer-0', text: '最初的回答', after_message_seq: 0 },
          { id: 'answer-1', text: '第一次补充的回答', after_message_seq: 1 },
        ],
      }}
      conversation={{ id: 'conversation', revision: 1, messages: supplements }}
      draftId="answer-2"
      draft="第二次补充的回答"
      draftAfterMessageSeq={2}
    />,
  );
  const text = [...container.querySelectorAll('[data-message-id]')].map((node) => node.textContent);
  expect(text).toHaveLength(6);
  [
    '最初的问题',
    '最初的回答',
    '补充要求1',
    '第一次补充的回答',
    '补充要求2',
    '第二次补充的回答',
  ].forEach((value, index) => {
    expect(text[index]).toContain(value);
  });
  expect(screen.getAllByLabelText('研究计划')).toHaveLength(1);
});

it('shows a newly accepted supplement while the run snapshot is still empty and preserves existing nodes', () => {
  const current = {
    ...run,
    messages: [],
    assistant_messages: [{ id: 'answer-0', text: '已经收到的回答', after_message_seq: 0 }],
  };
  const { rerender } = render(<HarnessConversation {...base} run={current} />);
  const answer = screen.getByText('已经收到的回答');
  const plan = screen.getByLabelText('研究计划');
  rerender(
    <HarnessConversation
      {...base}
      run={current}
      conversation={{
        id: 'conversation',
        revision: 2,
        messages: [
          {
            id: 'new',
            role: 'user',
            text: '刚收到的补充',
            seq: 1,
            run_id: run.id,
            status: 'PENDING',
          },
        ],
      }}
    />,
  );
  expect(screen.getByText('刚收到的补充')).toBeVisible();
  expect(screen.getByText('等待当前步骤结束后接收')).toBeVisible();
  expect(screen.getByText('已经收到的回答')).toBe(answer);
  expect(screen.getByLabelText('研究计划')).toBe(plan);
});

it('keeps requeued supplements only in the queue after stop and does not label history as pending', () => {
  const pending = {
    id: 'pending',
    role: 'user',
    text: '未接收的补充',
    seq: 1,
    run_id: run.id,
    status: 'PENDING' as const,
  };
  render(
    <HarnessConversation
      {...base}
      run={{ ...run, status: 'CANCELLED', messages: [pending] }}
      conversation={{
        id: 'conversation',
        revision: 2,
        queue_paused: true,
        messages: [
          pending,
          { id: 'old', role: 'user', text: '以前的补充', run_id: 'old-run', status: 'PENDING' },
        ],
        queued: [{ ...pending, status: 'QUEUED' }],
      }}
    />,
  );
  expect(screen.getAllByText('未接收的补充')).toHaveLength(1);
  expect(screen.getByText('以前的补充')).toBeVisible();
  expect(screen.queryByText('等待当前任务接收')).not.toBeInTheDocument();
  expect(screen.queryByText('等待当前步骤结束后接收')).not.toBeInTheDocument();
  expect(screen.getByText('已暂停，输入“继续”后接着处理')).toBeVisible();
});
