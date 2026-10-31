import { ArrowLeftOutlined } from '@ant-design/icons';
import { Button, Spin, Tabs, Typography } from 'antd';
import { lazy, Suspense } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { PagePermissionGate } from '@/routes/route-guards';
import './assistant-hub.css';

const OrdinaryAssistant = lazy(() =>
  import('./AssistantPage').then((module) => ({ default: module.AssistantPage })),
);
const ResearchAssistant = lazy(() =>
  import('../agents/ResearchWorkspacePage').then((module) => ({
    default: module.ResearchWorkspacePage,
  })),
);

/** UI composition only: no shared conversation IDs, prompts, stream store or API adapter. */
export function AssistantHubPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const mode = params.get('mode') === 'research' ? 'research' : 'qa';
  return (
    <div className="business-page assistant-hub">
      <header className="assistant-hub-heading">
        <Button
          type="text"
          icon={<ArrowLeftOutlined />}
          aria-label="返回上一页"
          onClick={() => navigate(-1)}
        />
        <div>
          <Typography.Title level={1}>AI问答</Typography.Title>
          <Typography.Text type="secondary">
            {mode === 'qa'
              ? '基于已授权资料回答，也可按需补充公开互联网信息。'
              : '查找文献、核对证据、比较研究并形成可追溯报告。'}
          </Typography.Text>
        </div>
      </header>
      <Tabs
        className="assistant-mode-tabs"
        activeKey={mode}
        animated={false}
        destroyOnHidden={false}
        onChange={(value) => {
          const next = new URLSearchParams(params);
          if (value === 'research') next.set('mode', 'research');
          else next.delete('mode');
          setParams(next, { replace: true });
        }}
        items={[
          {
            key: 'qa',
            label: '普通问答',
            children: (
              <ErrorBoundary>
                <Suspense
                  fallback={
                    <div role="status">
                      <Spin /> 加载普通问答…
                    </div>
                  }
                >
                  <OrdinaryAssistant />
                </Suspense>
              </ErrorBoundary>
            ),
          },
          {
            key: 'research',
            label: '研究助手',
            children: (
              <ErrorBoundary>
                <PagePermissionGate permission="ai.use">
                  <Suspense
                    fallback={
                      <div role="status">
                        <Spin /> 加载研究助手…
                      </div>
                    }
                  >
                    <ResearchAssistant />
                  </Suspense>
                </PagePermissionGate>
              </ErrorBoundary>
            ),
          },
        ]}
      />
    </div>
  );
}
