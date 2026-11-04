import { Alert, App, Button } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { agentApi } from '@/services/agents/agent-api';

export function ResearchOperations({
  runId,
  owner,
  active,
  showNotice = true,
}: {
  runId?: string;
  owner?: string;
  active: boolean;
  showNotice?: boolean;
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
  if (operations.isError)
    return (
      <Alert
        type="warning"
        message="暂时无法确认操作结果"
        action={<Button onClick={() => void operations.refetch()}>检查结果</Button>}
      />
    );
  const uncertain = (operations.data || []).filter((operation) => operation.status === 'UNKNOWN');
  if (!uncertain.length) return null;
  return (
    <section aria-label="待确认的结果">
      {showNotice && <p>结果还在确认，请先不要重复提交。</p>}
      {uncertain.map((operation, index) => (
        <Button
          key={operation.call_id}
          loading={checking === operation.call_id}
          disabled={Boolean(checking)}
          onClick={() => {
            setChecking(operation.call_id);
            void agentApi
              .reconcile(runId!, operation.call_id)
              .then((result) => {
                if (result.outcome === 'CONFIRMED_SUCCEEDED') void message.success('操作已完成。');
                else void message.info('结果仍在确认，请稍后再检查。');
                return operations.refetch();
              })
              .catch(() => void message.error('暂时无法确认，请稍后再检查。'))
              .finally(() => setChecking(undefined));
          }}
        >
          检查结果{uncertain.length > 1 ? ` ${index + 1}` : ''}
        </Button>
      ))}
    </section>
  );
}
