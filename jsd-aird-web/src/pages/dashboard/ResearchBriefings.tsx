import { ArrowUpOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Skeleton } from 'antd';
import { Link } from 'react-router-dom';
import { useAuthStore } from '@/stores/auth-store';
import { canViewPathWithMenus } from '@/routes/route-permissions';
import { useBusinessQuery } from '@/services/query/use-business-query';
import { getBriefings, type BriefingCard, type BriefingKind } from '@/services/agents/briefings-api';
import './research-briefings.css';

const definitions: { kind: BriefingKind; title: string; label: string; empty: string }[] = [
  { kind: 'project-morning', title: '项目晨报', label: '查看晨报', empty: '晨报生成后，在这里查看今日重点与建议行动。' },
  { kind: 'research-weekly', title: '研发周报', label: '查看周报', empty: '周报生成后，在这里查看本周进展与下一步安排。' },
  { kind: 'experiment-review', title: '实验复盘', label: '查看复盘', empty: '复盘生成后，在这里查看实验观察与后续建议。' },
];

function formatBriefingDate(value: string, time = false) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', month: 'numeric', day: 'numeric',
    ...(time ? { hour: '2-digit', minute: '2-digit', hour12: false } : {}),
  }).format(date);
}

function period(card: BriefingCard) {
  const span = card.result?.window;
  if (!span) return '';
  return span.start ? `${formatBriefingDate(span.start)}—${formatBriefingDate(span.end)}` : formatBriefingDate(span.end);
}

function stateMessage(card: BriefingCard | undefined) {
  if (!card) return '';
  if (card.state === 'UNAVAILABLE') return '内容暂不可用，请确认当前资料权限。';
  const states: Record<string, string> = { RUNNING: '本期正在生成', QUEUED: '本期等待生成', WAITING_APPROVAL: '本期等待处理',
    FAILED: '本期未完成', CANCELLED: '本期已停止', UNKNOWN: '本期状态待确认', INCOMPLETE: '本期内容不完整' };
  const state = states[card.state];
  if (state) return `${state}${card.result ? ' · 以下为最近一次完成的结果' : ''}`;
  if (card.task_status === 'paused') return '定时任务已暂停';
  if (card.task_status === 'deleted') return '定时任务已删除';
  if (card.task_status === 'target_changed') return '定时任务的目标会话已变更';
  return '';
}

function rememberReturn() {
  try { sessionStorage.setItem('jsd.ai-workbench.return', '/dashboard'); } catch { /* Navigation works without storage. */ }
}

function Briefing({ definition, card }: { definition: typeof definitions[number]; card?: BriefingCard }) {
  const user = useAuthStore(state => state.user);
  const result = card?.result;
  const morning = definition.kind === 'project-morning';
  const message = stateMessage(card);
  return (
    <article className={`research-briefing${morning ? ' research-briefing-main' : ''}`} aria-label={definition.title}>
      <div className="research-briefing-meta">
        <span>{definition.title}</span>
        {card && result ? <span>{period(card)}</span> : null}
      </div>
      {message ? <p className="research-briefing-status" role="status">{message}</p> : null}
      <h3>{result?.headline ?? (message ? '等待可用结果' : '尚未生成')}</h3>
      {result?.summary !== result?.headline || !result ? <p className="research-briefing-summary">{result?.summary || (!message ? definition.empty : '')}</p> : null}
      {morning && result && result.actions.length > 0 ? (
        <ol className="research-briefing-actions" aria-label="建议行动">
          {result.actions.map((action, index) => (
            <li key={index}>
              <span>{action.text}</span>
              {action.href && user && canViewPathWithMenus(action.href, user.permissions, user.menuKeys) ? <Link to={action.href}>查看记录 ↗</Link> : null}
            </li>
          ))}
        </ol>
      ) : null}
      <footer className="research-briefing-footer">
        {result ? <a className={morning ? 'research-briefing-primary' : 'research-briefing-link'} href={result.href} onClick={rememberReturn}>
          {definition.label}<ArrowUpOutlined rotate={45} aria-hidden="true" />
        </a> : <span className="research-briefing-pending">生成后可查看</span>}
        {result ? <time dateTime={result.generated_at}>{formatBriefingDate(result.generated_at, true)}</time> : null}
      </footer>
    </article>
  );
}

export function ResearchBriefings() {
  const user = useAuthStore(state => state.user);
  const allowed = Boolean(user && canViewPathWithMenus('/assistant', user.permissions, user.menuKeys));
  const query = useBusinessQuery('research-briefings', {}, getBriefings, { enabled: allowed });
  if (!allowed) return null;
  // A failed revalidation must never leave a stale sensitive summary visible.
  const items = query.isError ? [] : query.data?.items ?? [];
  return (
    <section className="research-briefings" aria-labelledby="research-briefings-title">
      <header className="research-briefings-heading">
        <h2 id="research-briefings-title">今日研判与行动</h2>
        <span>个人简报 · AI 生成</span>
      </header>
      {query.isError ? (
        <div className="research-briefings-error" role="alert">
          <span>暂时无法读取简报，请稍后重试。</span>
          <Button type="text" icon={<ReloadOutlined />} onClick={() => void query.refetch()}>重试</Button>
        </div>
      ) : query.isPending ? (
        <div className="research-briefings-loading" role="status" aria-label="正在读取简报"><Skeleton active paragraph={{ rows: 4 }} /></div>
      ) : (
        <div className="research-briefings-grid">
          <Briefing definition={definitions[0]!} card={items.find(item => item.kind === 'project-morning')} />
          <div className="research-briefings-secondary">
            {definitions.slice(1).map(definition => <Briefing key={definition.kind} definition={definition} card={items.find(item => item.kind === definition.kind)} />)}
          </div>
        </div>
      )}
    </section>
  );
}
