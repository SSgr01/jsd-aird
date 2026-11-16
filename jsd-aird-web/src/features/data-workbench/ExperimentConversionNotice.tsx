import { Alert, Button, Collapse, Space, Table } from 'antd';
import { useEffect, useState } from 'react';
import { dataApi, type ExperimentConversion } from '@/services/data/data-api';
import { triggerNativeDownload } from '@/services/files/file-api';

/** Keep conversion provenance beside the existing human confirmation controls. */
export function ExperimentConversionNotice({ importId, confirmed }: { importId: string; confirmed: boolean }) {
  const [task, setTask] = useState<ExperimentConversion | null>();
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let active = true; setFailed(false); setTask(undefined);
    void dataApi.conversionForImport(importId).then(value => { if (active) setTask(value); })
      .catch(() => { if (active) setFailed(true); });
    return () => { active = false; };
  }, [importId, attempt]);
  if (failed) return <Alert type="warning" message="转换来源信息暂不可读，请重试后再核对。" action={<Button onClick={() => setAttempt(value => value + 1)}>重试</Button>} />;
  if (!task) return null;
  const unplaced = task.report?.unplaced ?? [], conflicts = task.report?.source_conflicts ?? [];
  const rows = [...unplaced.map(value => ({ value, type: '未落位' })), ...conflicts.map(value => ({ value, type: '源合计冲突' }))].map(({ value, type }, index) => {
    const item = (value && typeof value === 'object' ? value : {}) as Record<string, unknown>;
    const display = (value: unknown) => value === null || value === undefined ? '空白' : typeof value === 'object' ? JSON.stringify(value) : String(value);
    return { key: index, type, source: [item.sheet, item.src].filter(Boolean).join(' · '), original: display(item.value ?? item.source_total),
      reason: display(item.reason), suggestion: item.computed_total !== undefined ? `按已填用量计算：${display(item.computed_total)}` : display(item.suggested_field) };
  });
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Alert type={confirmed ? 'info' : 'warning'} showIcon
      message={confirmed ? '此记录来自专家转换，导入数据已人工确认；实验草稿尚需发布。' : '专家转换结果待人工核对，机器校验通过不代表字段语义或实验数据已经人工确认。'}
      description={<Space wrap>
        <span>未落位 {unplaced.length} 项，源数据冲突 {conflicts.length} 项。原件与转换结果分别保留。</span>
        <Button type="link" onClick={() => triggerNativeDownload(`/api/v1/data/experiment-conversions/${task.id}/source`, task.sourceName)}>下载原始实验文件</Button>
        <Button type="link" href={`/agent/?conversion=${task.id}`}>查看转换过程</Button>
      </Space>} />
    {(unplaced.length > 0 || conflicts.length > 0) && <Collapse items={[{ key: 'issues', label: '查看未落位项与源数据冲突', children: <>
      <p>这些值未必已进入标准表，请对照原件逐项处理。原始问题清单保留，不随人工修改删除。</p>
      <Table size="small" dataSource={rows} pagination={{ pageSize: 10 }} scroll={{ x: 680 }} columns={[
        { title: '问题', dataIndex: 'type', width: 100 }, { title: '原件位置', dataIndex: 'source', width: 130 },
        { title: '原值', dataIndex: 'original', width: 120 }, { title: '原因', dataIndex: 'reason' }, { title: '处理参考', dataIndex: 'suggestion', width: 180 },
      ]} />
    </> }]} />}
  </Space>;
}
