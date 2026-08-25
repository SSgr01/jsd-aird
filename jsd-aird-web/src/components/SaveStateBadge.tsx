import { Tag } from 'antd';

export type SaveState = 'SAVED' | 'DIRTY' | 'SAVING';

export function SaveStateBadge({ state }: { state: SaveState }) {
  if (state === 'SAVING') return <Tag color="processing">保存中</Tag>;
  return state === 'DIRTY'
    ? <Tag color="warning">未保存</Tag>
    : <Tag color="success">已保存</Tag>;
}
