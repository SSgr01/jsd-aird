import {
  ArrowLeftOutlined,
  BookOutlined,
  DeleteOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  HistoryOutlined,
  InfoCircleOutlined,
  LinkOutlined,
  PlusOutlined,
  SaveOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  DatePicker,
  Form,
  Input,
  Modal,
  Select,
  Skeleton,
  Tabs,
  Tag,
  message,
} from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import dayjs from '@/utils/dayjs';

import {
  formatProjectStatus,
  deleteProjects,
  getProject,
  getProjects,
  projectPriorities,
  projectStatuses,
  updateProject,
  type Project,
  type ProjectInput,
} from '@/services/project/project-api';

import './project-detail.css';
import { CustomerRequirementTab } from './CustomerRequirementTab';
import { MeetingMinutesTab } from './MeetingMinutesTab';
import { ProjectMaterialsTab } from './ProjectMaterialsTab';
import { ReferenceMaterialsTab } from './ReferenceMaterialsTab';
import { ProjectStageBoard } from './ProjectStageBoard';
import { ProjectLogsTab } from './ProjectLogsTab';
import ProjectDocumentsTab from './ProjectDocumentsTab';

const detailTabKeys = new Set(['documents', 'info', 'requirements', 'references', 'meetings', 'logs', 'assets']);

interface InfoFormValues {
  projectCode?: string;
  name: string;
  owner?: string;
  startDate?: dayjs.Dayjs | null;
  priority?: Project['priority'];
  status?: Project['status'];
  teamMembers?: string[];
  background?: string;
  customFields?: { key: string; value: string }[];
}

export function ProjectDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [project, setProject] = useState<Project>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [saving, setSaving] = useState(false);
  const [messageApi, holder] = message.useMessage();
  const [form] = Form.useForm<InfoFormValues>();

  const loadProject = (projectId: string) =>
    getProject(projectId)
      .then(setProject)
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : '项目加载失败'))
      .finally(() => setLoading(false));

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    void loadProject(id);
  }, [id]);

  const members = useMemo(
    () => (Array.isArray(project?.teamMembers) ? project.teamMembers : []),
    [project?.teamMembers],
  );

  // project 加载 / 保存刷新后，回填表单值
  useEffect(() => {
    if (!project) return;
    form.setFieldsValue({
      projectCode: project.projectCode,
      name: project.name,
      owner: project.owner,
      startDate: project.startDate ? dayjs(project.startDate) : null,
      priority: project.priority,
      status: project.status,
      teamMembers: members,
      background: project.background,
      customFields: (project.customFields
        ? Object.entries(project.customFields).map(([key, value]) => ({
            key,
            value: value == null ? '' : String(value),
          }))
        : []),
    });
  }, [project, members, form]);

  const handleSave = async () => {
    if (!project || !id) return;
    let values: InfoFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const projectCode = values.projectCode?.trim();
    if (projectCode) {
      const result = await getProjects({ keyword: projectCode, page: 1, size: 200 });
      const duplicate = result.items.some(
        (item) => item.id !== project.id && item.projectCode.trim() === projectCode,
      );
      if (duplicate) {
        form.setFields([{ name: 'projectCode', errors: ['项目编号已存在'] }]);
        messageApi.error('项目编号已存在');
        return;
      }
    }

    const customFields = (values.customFields ?? []).reduce<Record<string, string>>((acc, item) => {
      const key = item?.key?.trim();
      if (!key) return acc;
      acc[key] = (item.value ?? '').toString();
      return acc;
    }, {});
    const input: ProjectInput = {
      projectCode: projectCode || undefined,
      name: values.name.trim(),
      owner: values.owner?.trim() || undefined,
      startDate: values.startDate?.format('YYYY-MM-DD') || undefined,
      priority: values.priority,
      status: values.status,
      teamSize: (values.teamMembers ?? []).length,
      background: values.background?.trim() || undefined,
      teamMembers: values.teamMembers ?? [],
      customFields: Object.keys(customFields).length ? customFields : undefined,
      version: project.version,
    };
    setSaving(true);
    try {
      await updateProject(id, input);
      await loadProject(id);
      messageApi.success('项目信息已保存');
    } catch (reason) {
      messageApi.error(reason instanceof Error ? reason.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleLifecycle = () => {
    if (!project) return;
    const archive = project.allowedActions?.includes('ARCHIVE');
    if (!archive && !project.allowedActions?.includes('DELETE')) return;
    Modal.confirm({
      title: `${archive ? '归档' : '删除'}项目“${project.name}”？`,
      content: archive ? '归档后项目不再出现在默认列表。' : '项目将从默认列表中隐藏。',
      okText: archive ? '归档' : '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteProjects([project.id]);
        messageApi.success(archive ? '项目已归档' : '项目已删除');
        navigate('/projects/list');
      },
    });
  };

  if (loading) return <Skeleton active paragraph={{ rows: 12 }} />;
  if (error || !project) return <Alert type="error" showIcon message="无法打开项目" description={error} />;

  const infoForm = (
    <div className="pm-info-card">
      <div className="pm-info-toolbar">
        <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={() => void handleSave()}>
          保存
        </Button>
      </div>

      <Form
        form={form}
        layout="vertical"
        className="pm-info-form"
        initialValues={
          project
                ? {
                    projectCode: project.projectCode,
                    name: project.name,
                    owner: project.owner,
                startDate: project.startDate ? dayjs(project.startDate) : null,
                priority: project.priority,
                status: project.status,
                teamMembers: members,
                background: project.background,
                customFields: project.customFields
                  ? Object.entries(project.customFields).map(([key, value]) => ({
                      key,
                      value: value == null ? '' : String(value),
                    }))
                  : [],
              }
            : undefined
        }
      >
        <div className="pm-form-grid">
          <Form.Item
            name="projectCode"
            label="项目编号"
            rules={[{ required: true, whitespace: true, message: '请输入项目编号' }, { max: 64, message: '最多 64 个字符' }]}
          >
            <Input placeholder="请输入项目编号（可修改）" maxLength={64} />
          </Form.Item>
          <Form.Item
            name="name"
            label="项目名称"
            rules={[{ required: true, message: '请输入项目名称' }, { max: 300, message: '最多 300 个字符' }]}
          >
            <Input placeholder="请输入项目名称" />
          </Form.Item>
          <Form.Item name="owner" label="负责人">
            <Input placeholder="请输入负责人" maxLength={100} />
          </Form.Item>
          <Form.Item name="startDate" label="开始日期">
            <DatePicker style={{ width: '100%' }} placeholder="请选择开始日期" />
          </Form.Item>
          <Form.Item name="priority" label="优先级">
            <Select placeholder="请选择" options={projectPriorities.map(({ value, label }) => ({ value, label }))} />
          </Form.Item>
          <Form.Item name="status" label="项目状态">
            <Select
              style={{ width: 220 }}
              placeholder="请选择"
              options={projectStatuses.map(({ value, label }) => ({ value, label }))}
            />
          </Form.Item>
          <Form.Item name="teamMembers" label="团队成员" className="pm-form-span-2">
            <Select
              mode="tags"
              placeholder="输入姓名后按回车"
              tokenSeparators={[',', '，', '\n']}
              style={{ width: '100%' }}
              allowClear
            />
          </Form.Item>
          <Form.Item name="background" label="项目描述" className="pm-form-span-2">
            <Input.TextArea rows={4} placeholder="请输入项目描述" />
          </Form.Item>

          <Form.List name="customFields">
            {(fields, { add, remove }) => (
              <div className="pm-form-span-2">
                <div className="pm-custom-field-toolbar">
                  <span>自定义字段</span>
                  <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={() => add({ key: '', value: '' })}>
                    添加字段
                  </Button>
                </div>

                {fields.length === 0 ? (
                  <div className="pm-custom-field-empty">未设置字段</div>
                ) : (
                  fields.map((field) => (
                    <div key={field.key} className="pm-custom-field-row">
                      <Form.Item
                        name={[field.name, 'key']}
                        rules={[{ required: true, message: '请输入字段名' }]}
                      >
                        <Input placeholder="字段名" maxLength={80} />
                      </Form.Item>
                      <Form.Item name={[field.name, 'value']}>
                        <Input placeholder="字段值" />
                      </Form.Item>
                      <Button
                        type="link"
                        size="small"
                        className="pm-custom-field-remove"
                        icon={<DeleteOutlined />}
                        onClick={() => remove(field.name)}
                        danger
                      />
                    </div>
                  ))
                )}
              </div>
            )}
          </Form.List>
        </div>
      </Form>
    </div>
  );

  const tabs = [
    { key: 'documents', label: <><FolderOpenOutlined /> 项目文档</>, children: <ProjectDocumentsTab projectId={id ?? project.id} projectCode={project.projectCode} projectName={project.name} /> },
    { key: 'info', label: <><InfoCircleOutlined /> 项目信息</>, children: infoForm },
    { key: 'requirements', label: <><FileTextOutlined /> 客户需求</>, children: <CustomerRequirementTab projectId={id ?? project.id} projectName={project.name} partnerId={project.partnerId} /> },
    { key: 'references', label: <><BookOutlined /> 资料参考</>, children: <ReferenceMaterialsTab projectId={id ?? project.id} /> },
    { key: 'meetings', label: <><TeamOutlined /> 会议纪要</>, children: <MeetingMinutesTab projectId={id ?? project.id} /> },
    { key: 'logs', label: <><HistoryOutlined /> 日志</>, children: <ProjectLogsTab projectId={id ?? project.id} /> },
    { key: 'assets', label: <><LinkOutlined /> 关联资料</>, children: <ProjectMaterialsTab projectId={id ?? project.id} /> },
  ];

  return (
    <div className="pm-detail-page">
      {holder}
      <div className="pm-detail-title">
        <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/projects/list')}>返回</Button>
        <h2>{project.name}</h2>
        <Tag color="blue">{formatProjectStatus(project.status)}</Tag>
        <span className="pm-detail-partner">关联客户：{project.partnerName ?? '—'}</span>
        <span style={{ marginLeft: 'auto' }}>
          {project.allowedActions?.includes('ARCHIVE') || project.allowedActions?.includes('DELETE') ? <Button danger={project.allowedActions?.includes('DELETE')} icon={project.allowedActions?.includes('ARCHIVE') ? undefined : <DeleteOutlined />} onClick={handleLifecycle}>{project.allowedActions?.includes('ARCHIVE') ? '归档' : '删除'}</Button> : null}
        </span>
      </div>

      <ProjectStageBoard projectId={id ?? project.id} />

      <Tabs activeKey={detailTabKeys.has(searchParams.get('section') ?? '') ? searchParams.get('section')! : 'info'}
        items={tabs} className="pm-detail-tabs"
        onChange={(key) => {
          const next = new URLSearchParams(searchParams);
          if (key === 'info') next.delete('section'); else next.set('section', key);
          setSearchParams(next, { replace: true });
        }} />
    </div>
  );
}
