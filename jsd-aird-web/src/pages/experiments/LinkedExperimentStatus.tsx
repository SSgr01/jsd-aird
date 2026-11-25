import { Button, Popover, Space } from 'antd';
import { useNavigate } from 'react-router-dom';
import { experimentStatusLabels, linkedExperimentStatusText, type LinkedExperimentStatus as Status } from '@/services/experiments/experiment-status';

export function LinkedExperimentStatus({ items }: { items?: Status[] }) {
  const navigate = useNavigate();
  if (!items?.length) return <span>实验状态暂不可用</span>;
  return <Popover trigger="click" content={<Space direction="vertical">{items.map(item =>
    <Button type="link" key={item.experimentId} onClick={() => navigate(`/experiments/${item.experimentId}`)}>
      {item.experimentNo || item.title || '打开实验'} · {experimentStatusLabels[item.status] || '状态暂不可用'}
    </Button>)}</Space>}>
    <Button type="link" size="small" style={{ paddingInline: 0, height: 'auto', whiteSpace: 'normal' }}>{linkedExperimentStatusText(items)}</Button>
  </Popover>;
}
