import { fireEvent, render, screen } from '@testing-library/react';
import { Button, Popconfirm } from 'antd';
import { RowActions } from './RowActions';

it('keeps a secondary destructive action behind its confirmation', async () => {
  const remove = vi.fn();
  render(<RowActions primary={<Button>查看</Button>}>
    <Popconfirm title="删除这条记录？" okText="确认删除" cancelText="保留记录" onConfirm={remove}>
      <Button danger>删除记录</Button>
    </Popconfirm>
  </RowActions>);
  fireEvent.click(screen.getByRole('button', { name: '更多操作' }));
  fireEvent.click(await screen.findByRole('button', { name: '删除记录' }));
  expect(await screen.findByText('删除这条记录？')).toBeInTheDocument();
  expect(remove).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '保留记录' }));
  expect(remove).not.toHaveBeenCalled();
});
