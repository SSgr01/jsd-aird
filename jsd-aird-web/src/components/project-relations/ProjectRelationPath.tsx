import { Tooltip } from 'antd';

import './project-relation-path.css';

interface Props {
  projectName?: string;
  stageName?: string;
  taskName?: string;
  /** 完全没有关联关系时的占位文案。 */
  emptyText?: string;
  className?: string;
}

/**
 * 统一的“项目 / 阶段 / 任务”层级展示。
 *
 * 项目名单独一行作为主信息，阶段与任务作为次要信息显示在第二行，
 * 缺失的层级不会留下多余的分隔符；完整路径通过 Tooltip 兜底展示。
 */
export function ProjectRelationPath({
  projectName,
  stageName,
  taskName,
  emptyText = '未关联项目',
  className,
}: Props) {
  const subPath = [stageName, taskName].filter(Boolean).join(' / ');
  const fullPath = [projectName, stageName, taskName].filter(Boolean).join(' / ');
  const classes = ['pm-relation-path', className].filter(Boolean).join(' ');

  if (!fullPath) return <span className={`${classes} pm-relation-path-empty`}>{emptyText}</span>;

  return (
    <Tooltip title={fullPath} placement="topLeft">
      <span className={classes}>
        <span
          className={
            projectName ? 'pm-relation-path-project' : 'pm-relation-path-project is-missing'
          }
        >
          {projectName || '未关联项目'}
        </span>
        {subPath && <span className="pm-relation-path-sub">{subPath}</span>}
      </span>
    </Tooltip>
  );
}
