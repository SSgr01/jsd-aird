import { Component, Suspense, useEffect, type PropsWithChildren, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { ContentLoading } from './ContentLoading';
import { StartupSkeleton } from './StartupSkeleton';
import { canRecoverRevision, checkBuildVersion, isChunkError, recoverBuild, useAvailableBuild } from '@/services/release/build-recovery';
import { resetFailedPages } from '@/routes/route-preload';
import { resetFailedEditors } from '@/features/template-workspace/editor-loader';
import { markPerformance, measurePerformance } from '@/services/performance/metrics';

class PageErrorBoundary extends Component<PropsWithChildren<{ resetKey: string }>, { error?: Error }> {
  state: { error?: Error } = {};
  static getDerivedStateFromError(error: Error) { return { error }; }
  componentDidCatch(error: Error) { if (isChunkError(error)) void checkBuildVersion(); }
  componentDidUpdate(previous: { resetKey: string }) {
    if (previous.resetKey !== this.props.resetKey && this.state.error) this.setState({ error: undefined });
  }
  render(): ReactNode {
    if (this.state.error) return <div className="content-error" role="alert"><h3>当前页面加载失败</h3><p>请检查网络后重试。</p><button type="button" onClick={() => { resetFailedPages(); resetFailedEditors(); this.setState({ error: undefined }); }}>重试当前页面</button></div>;
    return this.props.children;
  }
}

function ContentReady({ children, routeKey }: PropsWithChildren<{ routeKey: string }>) {
  useEffect(() => {
    markPerformance('route-code-ready');
    measurePerformance('route-code', 'route-start', 'route-code-ready');
  }, [routeKey]);
  return children;
}

export function ContentBoundary({ children, startup = false }: PropsWithChildren<{ startup?: boolean }>) {
  const location = useLocation();
  const build = useAvailableBuild();
  const standalone = location.pathname.startsWith('/render/') || location.pathname === '/change-password' || /^\/production-orders\/[^/]+\/workspace$/.test(location.pathname);
  return <>
    {build && (!startup || standalone) && <div className="query-feedback" role="status">系统版本已更新，请保存当前工作后重新加载。<button type="button" disabled={!canRecoverRevision(build.revision)} onClick={() => recoverBuild(build.revision)}>重新加载</button></div>}
    <PageErrorBoundary resetKey={location.key}>
      <Suspense fallback={startup ? <StartupSkeleton /> : <ContentLoading />}><ContentReady routeKey={location.key}>{children}</ContentReady></Suspense>
    </PageErrorBoundary>
  </>;
}
