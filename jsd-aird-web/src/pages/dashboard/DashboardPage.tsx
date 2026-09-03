import {
  AppstoreOutlined,
  BellOutlined,
  DatabaseOutlined,
  FileTextOutlined,
  FolderOutlined,
  HistoryOutlined,
  ProjectOutlined,
  ScheduleOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import { Button, Tooltip } from 'antd';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useEffect, useMemo, useState } from 'react';

import { inventoryApi } from '@/services/inventory/inventory-api';
import { listExperiments, type ExperimentSummary } from '@/services/experiments/experiment-api';
import { getProjects, getTasks, type Project, type ProjectTask } from '@/services/project/project-api';
import { DashboardQuickAction } from './DashboardQuickAction';
import './dashboard-prototype.css';

interface StatMetric {
  key: string;
  label: string;
  value: number;
  icon: ReactNode;
  tint: string;
  bg: string;
  to: string;
}

interface DashboardModule {
  key: string;
  title: string;
  entryName?: string;
  items: DashboardModuleItem[];
  emptyHint: string;
  to: string;
}

interface DashboardModuleItem {
  key: string;
  title: string;
  meta?: string;
  badge?: string;
  to?: string;
}

const DASHBOARD_STATS: StatMetric[] = [
  {
    key: 'projects',
    label: '我的项目',
    value: 0,
    to: '/projects/list',
    icon: <FolderOutlined />,
    tint: '#1f6feb',
    bg: '#e8f1ff',
  },
  {
    key: 'tasks',
    label: '我的任务',
    value: 0,
    to: '/projects/tasks',
    icon: <UnorderedListOutlined />,
    tint: '#8b5cf6',
    bg: '#f1ebff',
  },
  {
    key: 'experiments',
    label: '最近实验',
    value: 0,
    to: '/experiments/list',
    icon: <HistoryOutlined />,
    tint: '#0ea5e9',
    bg: '#e4f6ff',
  },
  {
    key: 'pending-review',
    label: '待审核',
    value: 0,
    to: '/experiments/list?status=PENDING_REVIEW',
    icon: <ScheduleOutlined />,
    tint: '#f59e0b',
    bg: '#fff5e0',
  },
  {
    key: 'to-be-filled',
    label: '待完善',
    value: 0,
    to: '/experiments/list?status=RETURNED',
    icon: <FileTextOutlined />,
    tint: '#10b981',
    bg: '#e6fbf2',
  },
  {
    key: 'inventory',
    label: '库存/复用',
    value: 0,
    to: '/inventory/query',
    icon: <DatabaseOutlined />,
    tint: '#475569',
    bg: '#eef1f6',
  },
];

const DASHBOARD_MODULES: DashboardModule[] = [
  {
    key: 'my-projects',
    title: '我的项目',
    items: [],
    emptyHint: '进入项目模块',
    entryName: '项目',
    to: '/projects/list',
  },
  {
    key: 'pending-review',
    title: '待审核事项',
    items: [],
    emptyHint: '进入审核模块',
    entryName: '审核',
    to: '/experiments/list?status=PENDING_REVIEW',
  },
  {
    key: 'to-be-filled',
    title: '待完善数据',
    items: [],
    emptyHint: '进入完善模块',
    entryName: '数据完善',
    to: '/experiments/list?status=RETURNED',
  },
  {
    key: 'inventory',
    title: '库存/临期提醒',
    items: [],
    emptyHint: '进入提醒模块',
    entryName: '库存',
    to: '/inventory/query',
  },
  {
    key: 'my-tasks',
    title: '我的任务',
    items: [],
    emptyHint: '进入任务模块',
    entryName: '任务',
    to: '/projects/tasks',
  },
  {
    key: 'recent-experiments',
    title: '最近实验',
    items: [],
    emptyHint: '进入实验模块',
    entryName: '实验',
    to: '/experiments/list',
  },
];

function fmtNumber(value: number): string {
  return value >= 1000 ? value.toLocaleString() : String(value);
}

function experimentItem(item: ExperimentSummary): DashboardModuleItem {
  return {
    key: item.id,
    title: item.title,
    meta: item.experimentNo,
    badge: item.status === 'PENDING_REVIEW' ? '待审核' : item.status === 'RETURNED' ? '待完善' : '实验',
    to: `/experiments/${item.id}`,
  };
}

function MetricCard({ metric }: { metric: StatMetric }) {
  return (
    <Link to={metric.to} className="dashboard-stat" style={{ ['--stat-tint' as string]: metric.tint, ['--stat-bg' as string]: metric.bg }}>
      <div className="dashboard-stat-icon">{metric.icon}</div>
      <div className="dashboard-stat-body">
        <div className="dashboard-stat-value">{fmtNumber(metric.value)}</div>
        <div className="dashboard-stat-label">{metric.label}</div>
      </div>
    </Link>
  );
}

function EmptyModule({ label, entryName }: { label: string; entryName?: string }) {
  return (
    <div className="dashboard-module-empty">
      <AppstoreOutlined className="dashboard-module-empty-icon" />
      <div className="dashboard-module-empty-text">{label}</div>
      <div className="dashboard-module-empty-cta">进入模块{entryName ? `“${entryName}”` : ''}</div>
    </div>
  );
}

function ModuleCard({ module }: { module: DashboardModule }) {
  const hasItems = module.items.length > 0;
  return (
    <section className="dashboard-module">
      <header className="dashboard-module-head">
        <h4 className="dashboard-module-title">{module.title}</h4>
        <Link to={module.to} className="dashboard-module-more">查看全部</Link>
      </header>
      <div className={`dashboard-module-body${hasItems ? ' has-items' : ' is-empty'}`}>
        {hasItems ? (
          <ul className="dashboard-module-list">
            {module.items.map((item) => (
              <li key={item.key} className="dashboard-module-item">
                {item.to ? (
                  <Link to={item.to} className="dashboard-module-item-main">
                    <span className="dashboard-module-item-title">{item.title}</span>
                    {item.meta ? <span className="dashboard-module-item-meta">{item.meta}</span> : null}
                  </Link>
                ) : (
                  <span className="dashboard-module-item-main">
                    <span className="dashboard-module-item-title">{item.title}</span>
                    {item.meta ? <span className="dashboard-module-item-meta">{item.meta}</span> : null}
                  </span>
                )}
                {item.badge ? (
                  <span className="dashboard-module-item-badge">{item.badge}</span>
                ) : null}
              </li>
            ))}
          </ul>
        ) : (
          <EmptyModule label="暂无相关事项" entryName={module.entryName} />
        )}
      </div>
    </section>
  );
}

export function DashboardPage() {
  const [inventoryReminders, setInventoryReminders] = useState<DashboardModuleItem[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectTotal, setProjectTotal] = useState(0);
  const [tasks, setTasks] = useState<ProjectTask[]>([]);
  const [taskTotal, setTaskTotal] = useState(0);
  const [experiments, setExperiments] = useState<ExperimentSummary[]>([]);
  const [experimentTotal, setExperimentTotal] = useState(0);
  const [pendingReviews, setPendingReviews] = useState<ExperimentSummary[]>([]);
  const [pendingReviewTotal, setPendingReviewTotal] = useState(0);
  const [returnedExperiments, setReturnedExperiments] = useState<ExperimentSummary[]>([]);
  const [returnedTotal, setReturnedTotal] = useState(0);
  const [inventoryAlertTotal, setInventoryAlertTotal] = useState(0);

  useEffect(() => {
    void Promise.all([
      getProjects({ page: 1, size: 6 }),
      getTasks({ page: 1, size: 6 }),
      listExperiments({ page: 1, size: 6 }),
      listExperiments({ status: 'PENDING_REVIEW', page: 1, size: 6 }),
      listExperiments({ status: 'RETURNED', page: 1, size: 6 }),
      inventoryApi.balances({ page: 1, size: 100 }),
    ])
      .then(([projectData, taskData, experimentData, reviewData, returnedData, inventoryData]) => {
        setProjects(projectData.items);
        setProjectTotal(projectData.total);
        setTasks(taskData.items);
        setTaskTotal(taskData.total);
        setExperiments(experimentData.items);
        setExperimentTotal(experimentData.total);
        setPendingReviews(reviewData.items);
        setPendingReviewTotal(reviewData.total);
        setReturnedExperiments(returnedData.items);
        setReturnedTotal(returnedData.total);
        setInventoryAlertTotal(inventoryData.summary.lowStockCount + inventoryData.summary.outOfStockCount);
        const reminders = inventoryData.items
          .filter((item) => item.alertStatus !== 'NORMAL')
          .slice(0, 6)
          .map((item) => ({
            key: `${item.scope}-${item.productId}`,
            title: `${item.productName} · ${item.alertStatus === 'LOW_STOCK' ? '低库存' : '无库存'}`,
            meta: `${item.productCode} / ${item.quantityKg} KG`,
            badge: '库存提醒',
            to: '/inventory/query',
          }));
        setInventoryReminders(reminders);
      })
      .catch(() => {
        setInventoryReminders([]);
      });
  }, []);

  const dashboardStats = useMemo(() => DASHBOARD_STATS.map((metric) => ({
    ...metric,
    value: {
      projects: projectTotal,
      tasks: taskTotal,
      experiments: experimentTotal,
      'pending-review': pendingReviewTotal,
      'to-be-filled': returnedTotal,
      inventory: inventoryAlertTotal,
    }[metric.key] ?? 0,
  })), [projectTotal, taskTotal, experimentTotal, pendingReviewTotal, returnedTotal, inventoryAlertTotal]);

  const dashboardModules = useMemo(
    () => DASHBOARD_MODULES.map((module) => ({
      ...module,
      items: {
        'my-projects': projects.map((item) => ({ key: item.id, title: item.name, meta: item.projectCode, badge: '项目', to: `/projects/${item.id}` })),
        'pending-review': pendingReviews.map(experimentItem),
        'to-be-filled': returnedExperiments.map(experimentItem),
        inventory: inventoryReminders,
        'my-tasks': tasks.map((item) => ({ key: item.id, title: item.name, meta: item.projectName, badge: '任务', to: '/projects/tasks' })),
        'recent-experiments': experiments.map(experimentItem),
      }[module.key] ?? [],
    })),
    [projects, pendingReviews, returnedExperiments, inventoryReminders, tasks, experiments],
  );

  return (
    <div className="dashboard-page">
      <header className="dashboard-page-head">
        <div>
          <h2 className="dashboard-page-title">工作台</h2>
          <p className="dashboard-page-desc">聚合我的研发事项，所有处理均在原业务模块完成。</p>
        </div>
        <div className="dashboard-page-extra">
          <span className="dashboard-page-username">张伟 · 研发工程师</span>
          <Tooltip title="通知中心">
            <Button shape="circle" icon={<BellOutlined />} aria-label="通知" />
          </Tooltip>
          <Tooltip title="近期活动">
            <Button shape="circle" icon={<HistoryOutlined />} aria-label="近期活动" />
          </Tooltip>
        </div>
      </header>

      <section className="dashboard-stats">
        {dashboardStats.map((metric) => (
          <MetricCard key={metric.key} metric={metric} />
        ))}
      </section>

      <section className="dashboard-modules">
        <div className="dashboard-modules-row dashboard-modules-row-primary">
          <ModuleCard module={dashboardModules[0]!} />
        </div>
        <div className="dashboard-modules-row dashboard-modules-row-secondary">
          {dashboardModules.slice(1, 4).map((m) => (
            <ModuleCard key={m.key} module={m} />
          ))}
        </div>
        <div className="dashboard-modules-row dashboard-modules-row-tertiary">
          {dashboardModules.slice(4).map((m) => (
            <ModuleCard key={m.key} module={m} />
          ))}
        </div>
      </section>

      <DashboardQuickAction />

      <div className="dashboard-page-footer" aria-hidden="true">
        <ProjectOutlined /> <span>研发数据中台 · 一站式工作台</span>
      </div>
    </div>
  );
}
