import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Select, Space, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';

import {
  getProjects,
  getProjectStages,
  getStageTasks,
  type Project,
  type ProjectStage,
  type ProjectTask,
} from '@/services/project/project-api';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';

interface Props {
  value: ProjectRelationTarget[];
  onChange: (value: ProjectRelationTarget[]) => void;
  disabled?: boolean;
  emptyText?: string;
}

const keyOf = (item: ProjectRelationTarget) => `${item.projectId}|${item.stageId || ''}|${item.taskId || ''}`;

export function ProjectRelationPicker({ value, onChange, disabled, emptyText = '尚未关联项目' }: Props) {
  const [projects, setProjects] = useState<Project[]>([]);
  const [stages, setStages] = useState<Record<string, ProjectStage[]>>({});
  const [tasks, setTasks] = useState<Record<string, ProjectTask[]>>({});

  useEffect(() => {
    let active = true;
    void getProjects({ page: 1, size: 100 }).then((page) => { if (active) setProjects(page.items); });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    const projectIds = [...new Set(value.map((item) => item.projectId).filter(Boolean))];
    projectIds.forEach((projectId) => {
      if (stages[projectId]) return;
      void getProjectStages(projectId).then((items) => setStages((current) => ({ ...current, [projectId]: items })));
    });
    const stageIds = [...new Set(value.map((item) => item.stageId).filter((id): id is string => Boolean(id)))];
    stageIds.forEach((stageId) => {
      if (tasks[stageId]) return;
      void getStageTasks(stageId).then((items) => setTasks((current) => ({ ...current, [stageId]: items })));
    });
  }, [stages, tasks, value]);

  const normalized = useMemo(() => {
    const seen = new Set<string>();
    return value.filter((item) => !seen.has(keyOf(item)) && seen.add(keyOf(item)));
  }, [value]);

  const update = (index: number, patch: Partial<ProjectRelationTarget>) => {
    const next = normalized.map((item, itemIndex) => itemIndex === index ? { ...item, ...patch } : item);
    const seen = new Set<string>();
    onChange(next.filter((item) => !seen.has(keyOf(item)) && seen.add(keyOf(item))));
  };

  return <Space direction="vertical" size={8} style={{ width: '100%' }}>
    {!normalized.length && <Typography.Text type="secondary">{emptyText}</Typography.Text>}
    {normalized.map((item, index) => <Space key={`${keyOf(item)}-${index}`} wrap align="start" style={{ width: '100%' }}>
      <Select
        showSearch
        optionFilterProp="label"
        placeholder="选择项目"
        value={item.projectId || undefined}
        disabled={disabled}
        style={{ width: 220 }}
        options={projects.map((project) => ({ value: project.id, label: `${project.projectCode} · ${project.name}` }))}
        onChange={(projectId) => {
          update(index, { projectId, stageId: undefined, taskId: undefined });
          if (!stages[projectId]) void getProjectStages(projectId).then((items) => setStages((current) => ({ ...current, [projectId]: items })));
        }}
      />
      <Select
        allowClear
        placeholder="阶段（可选）"
        value={item.stageId}
        disabled={disabled || !item.projectId}
        style={{ width: 180 }}
        options={(stages[item.projectId] || []).map((stage) => ({ value: stage.id, label: stage.name }))}
        onChange={(stageId) => {
          update(index, { stageId, taskId: undefined });
          if (stageId && !tasks[stageId]) void getStageTasks(stageId).then((items) => setTasks((current) => ({ ...current, [stageId]: items })));
        }}
      />
      <Select
        allowClear
        placeholder="任务（可选）"
        value={item.taskId}
        disabled={disabled || !item.stageId}
        style={{ width: 180 }}
        options={(item.stageId ? tasks[item.stageId] || [] : []).map((task) => ({ value: task.id, label: task.name }))}
        onChange={(taskId) => update(index, { taskId })}
      />
      {!disabled && <Button aria-label="删除项目关系" icon={<DeleteOutlined />} onClick={() => onChange(normalized.filter((_, itemIndex) => itemIndex !== index))} />}
    </Space>)}
    {!disabled && <Button type="dashed" icon={<PlusOutlined />} onClick={() => onChange([...normalized, { projectId: '' }])}>添加项目关系</Button>}
  </Space>;
}
