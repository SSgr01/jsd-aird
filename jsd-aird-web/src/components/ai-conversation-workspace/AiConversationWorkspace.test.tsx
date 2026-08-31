import { fireEvent, render, screen } from '@testing-library/react';

import { AiConversationWorkspace } from './AiConversationWorkspace';

describe('AiConversationWorkspace', () => {
  it('keeps user messages on the user side without rendering a user avatar', () => {
    render(
      <AiConversationWorkspace
        conversations={[]}
        messages={[
          { id: 'user-1', role: 'USER', content: <p>用户问题</p> },
          { id: 'assistant-1', role: 'ASSISTANT', content: <p>助手回答</p> },
        ]}
        scopeContent={<div>研发知识库</div>}
        assistantLabel="AI图谱助手"
        question=""
        onNewConversation={vi.fn()}
        onSelectConversation={vi.fn()}
        onQuestionChange={vi.fn()}
        onSubmit={vi.fn()}
      />,
    );

    const userMessage = screen.getByText('用户问题').closest('.ai-message');
    const assistantMessage = screen.getByText('助手回答').closest('.ai-message');

    expect(userMessage).toHaveClass('ai-message-user');
    expect(userMessage?.querySelector('.ai-message-avatar')).toBeNull();
    expect(userMessage?.textContent).toContain('你');
    expect(userMessage?.textContent).not.toContain('我');
    expect(assistantMessage).toHaveClass('ai-message-assistant');
    expect(assistantMessage?.querySelector('.ai-message-assistant-icon')).toBeInTheDocument();
    expect(assistantMessage?.textContent).toContain('AI图谱助手');
  });

  it('enforces the composer limit and submits with Enter', () => {
    const onQuestionChange = vi.fn();
    const onSubmit = vi.fn();

    render(
      <AiConversationWorkspace
        conversations={[]}
        messages={[]}
        scopeContent={<div>研发知识库</div>}
        composerTopContent={<div>全部已授权资料</div>}
        question="测试问题"
        onNewConversation={vi.fn()}
        onSelectConversation={vi.fn()}
        onQuestionChange={onQuestionChange}
        onSubmit={onSubmit}
      />,
    );

    const composer = screen.getByRole('textbox', { name: '输入问题' });
    expect(composer).toHaveAttribute('maxlength', '2000');
    fireEvent.change(composer, { target: { value: '新的问题' } });
    fireEvent.keyDown(composer, { key: 'Enter', code: 'Enter' });

    expect(onQuestionChange).toHaveBeenCalledWith('新的问题');
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('blocks click and Enter submission when the current scope is incomplete', () => {
    const onSubmit = vi.fn();

    render(
      <AiConversationWorkspace
        conversations={[]}
        messages={[]}
        scopeContent={<div>图谱选择</div>}
        question="分析这些图谱"
        submitDisabled
        onNewConversation={vi.fn()}
        onSelectConversation={vi.fn()}
        onQuestionChange={vi.fn()}
        onSubmit={onSubmit}
      />,
    );

    const composer = screen.getByRole('textbox', { name: '输入问题' });
    const submit = screen.getByRole('button', { name: '发送问题' });

    expect(submit).toBeDisabled();
    fireEvent.keyDown(composer, { key: 'Enter', code: 'Enter' });
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('renders controlled composer actions next to the retrieval scope', () => {
    render(
      <AiConversationWorkspace
        conversations={[]}
        messages={[]}
        scopeContent={<div>资料范围</div>}
        composerTopContent={<div>全部已授权资料</div>}
        composerActions={<button type="button" aria-pressed="false">联网搜索</button>}
        question=""
        onNewConversation={vi.fn()}
        onSelectConversation={vi.fn()}
        onQuestionChange={vi.fn()}
        onSubmit={vi.fn()}
      />,
    );

    expect(screen.getByRole('button', { name: '联网搜索' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByText('全部已授权资料')).toBeInTheDocument();
  });
});
