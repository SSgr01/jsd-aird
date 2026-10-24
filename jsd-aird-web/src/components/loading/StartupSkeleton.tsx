export function StartupSkeleton({ error, onRetry }: { error?: boolean; onRetry?: () => void }) {
  const login = window.location.pathname === '/login';
  return <div className={`startup-skeleton${login ? ' startup-login' : ''}`} role="status" aria-label="正在打开应用">
    <aside className="startup-sidebar"><i /><i /><i /><i /><i /><i /></aside>
    <header className="startup-header"><i /></header>
    <div className="startup-content"><i /><div className="startup-panel"><i /><i /><i /></div>
      {error && <p role="alert">暂时无法连接服务。<button type="button" onClick={onRetry}>重新检查</button></p>}
    </div>
  </div>;
}
