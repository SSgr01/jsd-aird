import {
  ArrowLeftOutlined,
  BookOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  HistoryOutlined,
  InfoCircleOutlined,
  LinkOutlined,
  SaveOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  DatePicker,
  Form,
  Input,
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
  getProject,
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
  name: string;
  owner?: string;
  startDate?: dayjs.Dayjs | null;
  priority?: Project['priority'];
  status?: Project['status'];
  teamMembers?: string[];
  background?: string;
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
      name: project.name,
      owner: project.owner,
      startDate: project.startDate ? dayjs(project.startDate) : null,
      priority: project.priority,
      status: project.status,
      teamMembers: members,
      background: project.background,
    });
  }, [project, members, form]);

  const handleSave = async () => {
    if (!project || !id) return;
    const values = await form.validateFields();
    const input: ProjectInput = {
      name: values.name.trim(),
      owner: values.owner?.trim() || undefined,
      startDate: values.startDate?.format('YYYY-MM-DD') || undefined,
      priority: values.priority,
      status: values.status,
      teamSize: (values.teamMembers ?? []).length,
      background: values.background?.trim() || undefined,
      teamMembers: values.teamMembers ?? [],
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
                name: project.name,
                owner: project.owner,
                startDate: project.startDate ? dayjs(project.startDate) : null,
                priority: project.priority,
                status: project.status,
                teamMembers: members,
                background: project.background,
              }
            : undefined
        }
      >
        <div className="pm-form-grid">
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
          <Form.Item name="status" label="项目状态" className="pm-form-span-2">
            <Select placeholder="请选择" options={projectStatuses.map(({ value, label }) => ({ value, label }))} />
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
