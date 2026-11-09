import { ArrowLeftOutlined } from '@ant-design/icons';
import { Button, Spin, Tabs, Typography } from 'antd';
import { lazy, Suspense, useLayoutEffect, useRef } from 'react';
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
  const hub = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const root = hub.current;
    if (!root) return;
    const update = () => {
      const canvas = root.querySelector('.ant-tabs-tabpane-active .ai-conversation-workspace');
      if (canvas)
        root.style.setProperty(
          '--assistant-workspace-height',
          `${Math.max(240, window.innerHeight - canvas.getBoundingClientRect().top - 12)}px`,
        );
    };
    const observer = new ResizeObserver(update);
    observer.observe(root);
    window.addEventListener('resize', update);
    update();
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', update);
    };
  }, [mode]);
  return (
    <div ref={hub} className="business-page assistant-hub">
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
              ? '结合企业资料，为你解答工作中的问题。'
              : '阅读文献、分析数据，整理你需要的研究结果。'}
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
