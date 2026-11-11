import { App, Button, ConfigProvider, Form, Input } from 'antd';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { FormModal } from './FormModal';

function Dialog({ save = () => Promise.resolve() }: { save?: () => Promise<void> }) {
  const [open, setOpen] = useState(true);
  const [revision, setRevision] = useState(0);
  const [form] = Form.useForm();
  return <App><ConfigProvider theme={{ token: { motion: false } }}>
    <Button onClick={() => setOpen(true)}>打开</Button>
    <FormModal open={open} title="编辑项目" form={form} resetKey={revision} onCancel={() => setOpen(false)}
      onOk={async () => { await save(); setRevision(value => value + 1); }} okText="保存" cancelText="取消">
      <Form form={form} layout="vertical" initialValues={{ name: '初始名称' }}>
        <Form.Item name="name" label="项目名称" rules={[{ required: true, message: '请输入名称' }]}><Input /></Form.Item>
        <Button onClick={() => form.setFieldsValue({ name: '异步加载的名称' })}>加载初始数据</Button>
      </Form>
    </FormModal>
  </ConfigProvider></App>;
}

describe('form dialog session', () => {
  it('does not treat programmatic initial values as unsaved user edits', async () => {
    render(<Dialog />);
    fireEvent.click(screen.getByRole('button', { name: '加载初始数据' }));
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.queryByText('放弃未保存的修改？')).not.toBeInTheDocument();
  });

  it('retains modified values when the user chooses to continue editing', async () => {
    render(<Dialog />);
    fireEvent.change(screen.getByRole('textbox', { name: '项目名称' }), { target: { value: '修改后' } });
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
    expect(await screen.findByText('放弃未保存的修改？', { selector: '.ant-modal-confirm-title' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '继续编辑' }));
    await waitFor(() => expect(screen.queryByText('放弃未保存的修改？', { selector: '.ant-modal-confirm-title' })).not.toBeInTheDocument());
    expect(screen.getByRole('textbox', { name: '项目名称' })).toHaveValue('修改后');
  });

  it('prevents duplicate requests, retains input after failure and permits retry', async () => {
    let rejectSave: (reason: Error) => void = () => undefined;
    const save = vi.fn().mockImplementationOnce(() => new Promise<void>((_, reject) => { rejectSave = reject; })).mockResolvedValue(undefined);
    render(<Dialog save={save} />);
    fireEvent.change(screen.getByRole('textbox', { name: '项目名称' }), { target: { value: '待保存' } });
    const button = screen.getByRole('button', { name: /保\s*存/ });
    fireEvent.click(button); fireEvent.click(button);
    await waitFor(() => expect(save).toHaveBeenCalledTimes(1));
    rejectSave(new Error('网络不可用'));
    expect(await screen.findByText('网络不可用')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '项目名称' })).toHaveValue('待保存');
    fireEvent.click(button);
    await waitFor(() => expect(save).toHaveBeenCalledTimes(2));
  });

  it('closes without an unsaved warning after an in-place save succeeds', async () => {
    const save = vi.fn().mockResolvedValue(undefined);
    render(<Dialog save={save} />);
    fireEvent.change(screen.getByRole('textbox', { name: '项目名称' }), { target: { value: '已保存的策略' } });
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(save).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByRole('button', { name: /取\s*消/ })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.queryByText('放弃未保存的修改？')).not.toBeInTheDocument();
  });
});
