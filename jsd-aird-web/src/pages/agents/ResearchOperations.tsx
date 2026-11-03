import { Alert, App, Button, List, Space, Tag, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { agentApi } from '@/services/agents/agent-api';

export function ResearchOperations({
  runId,
  owner,
  active,
}: {
  runId?: string;
  owner?: string;
  active: boolean;
}) {
  const { message } = App.useApp();
  const [checking, setChecking] = useState<string>();
  const operations = useQuery({
    queryKey: ['harness-operations', owner, runId],
    queryFn: () => agentApi.operations(runId!),
    enabled: Boolean(runId),
    refetchInterval: (query) =>
      active || query.state.data?.some((item) => item.status === 'UNKNOWN') ? 3000 : false,
  });
  const labels = {
    RUNNING: '处理中',
    SUCCEEDED: '已取得结果',
    FAILED: '执行失败',
    UNKNOWN: '结果待核实',
  };
  const names: Record<string, string> = {
    note_save: '保存研究笔记',
    evidence_save: '保存证据卡',
    report_save: '保存报告',
    artifact_save: '登记文件',
    matrix_record: '更新证据矩阵',
    document_read: '读取文献',
    knowledge_search: '检索资料',
    private_document_read: '读取私有资料',
    sandbox_execute: '隔离分析',
    workspace_write: '写入临时文件',
  };
  return (
    <section className="research-operations" aria-label="执行回执">
      <Typography.Title level={4}>执行回执</Typography.Title>
      {operations.isError && (
        <Alert
          type="error"
          message="暂时无法读取回执"
          action={<Button onClick={() => void operations.refetch()}>刷新</Button>}
        />
      )}
      <List
        size="small"
        loading={operations.isFetching && !operations.data}
        dataSource={operations.data || []}
        locale={{ emptyText: '本轮尚无工具操作' }}
        renderItem={(operation) => (
          <List.Item
            actions={
              operation.status === 'UNKNOWN'
                ? [
                    <Button
                      key="check"
                      loading={checking === operation.call_id}
                      disabled={Boolean(checking)}
                      onClick={() => {
                        setChecking(operation.call_id);
                        void agentApi
                          .reconcile(runId!, operation.call_id)
                          .then((result) => {
                            if (result.outcome === 'CONFIRMED_SUCCEEDED')
                              void message.success('已核对权威回执，未重复执行。');
                            else void message.info('仍未取得权威回执，继续保留结果待核实状态。');
                            return operations.refetch();
                          })
                          .catch(() => void message.error('核对未完成，可稍后再次读取回执。'))
                          .finally(() => setChecking(undefined));
                      }}
                    >
                      核对回执
                    </Button>,
                  ]
                : []
            }
          >
            <List.Item.Meta
              title={
                <Space wrap>
                  <span>{names[operation.tool_name] || '工具操作'}</span>
                  <Tag>{labels[operation.status]}</Tag>
                </Space>
              }
              description={
                operation.receipt_id
                  ? `回执 ${operation.receipt_id}`
                  : operation.status === 'UNKNOWN'
                    ? '尚不能确认是否已完成，请勿重复提交。'
                    : new Date(operation.updated_at).toLocaleString()
              }
            />
          </List.Item>
        )}
      />
    </section>
  );
}
