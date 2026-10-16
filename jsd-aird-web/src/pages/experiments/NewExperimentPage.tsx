import { EditOutlined, ExperimentOutlined, FileExcelOutlined, FileTextOutlined, FileWordOutlined, SearchOutlined } from '@ant-design/icons';
import { Alert, App, Breadcrumb, Button, Empty, Form, Input, Select, Skeleton, Typography } from 'antd';
import dayjs from 'dayjs';
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';
import { createExperiment, listExperiments, type ExperimentSummary } from '@/services/experiments/experiment-api';
import { useAuthStore } from '@/stores/auth-store';
import { dataApi, type DataTemplateOption } from '@/services/data/data-api';

import './experiments.css';

type DocumentFormat = 'word' | 'excel';
type RecordMode = DocumentFormat | 'template';

const visibilityOptions = [
  { value: 'ALL', label: '全员可见' },
  { value: 'RND', label: '研发部可见' },
  { value: 'PROJECT', label: '项目组可见' },
];

export function NewExperimentPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const user = useAuthStore((state) => state.user);
  const [form] = Form.useForm();
  const [recordMode, setRecordMode] = useState<RecordMode>('word');
  const [templates, setTemplates] = useState<DataTemplateOption[]>([]);
  const [templatesLoading, setTemplatesLoading] = useState(false);
  const [relations, setRelations] = useState<ProjectRelationTarget[]>([]);
  const [recent, setRecent] = useState<ExperimentSummary[]>([]);
  const [loadingRecent, setLoadingRecent] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [creationError, setCreationError] = useState('');
  const [keyword, setKeyword] = useState('');

  useEffect(() => {
    form.setFieldsValue({
      title: '',
      visibility: 'ALL',
    });
    void listExperiments({ page: 1, size: 14 })
      .then((result) => setRecent(result.items))
      .catch(() => void message.error('最近实验记录加载失败'))
      .finally(() => setLoadingRecent(false));
  }, [form, message]);

  const filteredRecent = useMemo(() => {
    const query = keyword.trim().toLowerCase();
    if (!query) return recent;
    return recent.filter((item) =>
      [item.experimentNo, item.title, item.ownerName].some((value) => value?.toLowerCase().includes(query)),
    );
  }, [keyword, recent]);

  const templateOptions = useMemo(() => {
    const groups = new Map<string, DataTemplateOption[]>();
    templates.forEach((item) => {
      const category = item.category?.trim() || '未分类';
      groups.set(category, [...(groups.get(category) || []), item]);
    });
    return [...groups.entries()].map(([label, items]) => ({
      label,
      options: items.map((item) => ({
        value: item.versionId,
        label: `${item.name}（Excel · V${item.versionNo}）`,
      })),
    }));
  }, [templates]);

  const changeRecordMode = (mode: RecordMode) => {
    setCreationError('');
    setRecordMode(mode);
    form.setFieldValue('templateVersionId', undefined);
    if (mode === 'template' && !templates.length) {
      setTemplatesLoading(true);
      void dataApi.listTemplates()
        .then((result) => setTemplates(result.filter((item) => item.format === 'XLSX'
          && item.importContractVersion === 9 && item.templateUsage === 'EXPERIMENT_DATA'
          && item.experimentImportReady)))
        .catch(() => void message.error('模板列表加载失败'))
        .finally(() => setTemplatesLoading(false));
    }
  };

  const submit = async () => {
    const values = await form.validateFields();
    const relation = relations.find((item) => item.projectId);
    setCreationError('');
    setSubmitting(true);
    try {
      const documentFormat: DocumentFormat = recordMode === 'template' ? 'excel' : recordMode;
      const item = await createExperiment({
        experimentNo: `EXP-${dayjs().format('YYYYMMDD')}-${String(Date.now()).slice(-6)}`,
        title: values.title.trim(),
        ownerName: user?.displayName || user?.username || '当前用户',
        experimentDate: dayjs().format('YYYY-MM-DD'),
        visibility: values.visibility,
        sourceType: recordMode === 'template' ? 'TEMPLATE' : 'MANUAL',
        templateVersionId: recordMode === 'template' ? values.templateVersionId : undefined,
        ...(relation ? { projectId: relation.projectId, stageId: relation.stageId, taskId: relation.taskId } : {}),
        editModel: {
          title: values.title.trim(),
          purpose: '',
          plan: '',
          documentFormat,
          blankDocument: recordMode !== 'template',
          dynamicValues: {},
          formulaItems: [],
          processSteps: [],
          testResults: [],
          events: [],
          conclusion: { resultStatus: '', mainConclusion: '', failureCategory: '' },
        },
      });
      void message.success('实验已创建，正在进入编辑');
      navigate(`/experiments/${item.id}`, { state: { returnTo: '/experiments/new' } });
    } catch (error) {
      const detail = error instanceof Error ? error.message : '实验创建失败';
      setCreationError(detail);
      void message.error(detail);
    } finally {
      setSubmitting(false);
    }
  };

  return <div className="upload-workspace-page eln-entry-page">
    <div className="upload-workspace-heading">
      <div>
        <Breadcrumb items={[{ title: '电子实验记录本' }, { title: '实验在线记录' }]} />
        <Typography.Title level={2}>实验在线记录</Typography.Title>
        <Typography.Text type="secondary">在线创建 Word 或 Excel 实验记录，并关联项目、阶段与任务。</Typography.Text>
      </div>
      <div className="upload-workspace-heading-actions"><Button onClick={() => navigate('/experiments/list')}>实验查看</Button></div>
    </div>
    <div className="upload-workspace-grid">
    <section className="upload-workspace-panel upload-workspace-panel-left eln-entry-main" aria-labelledby="new-experiment-title">
      <div className="upload-workspace-section-title" id="new-experiment-title">基础信息</div>
      <Form form={form} layout="vertical" className="upload-workspace-classification">
        <div className="eln-entry-top-grid">
          <Form.Item name="title" label="文件名称" rules={[{ required: true, whitespace: true, message: '请输入文件名称' }]}>
            <Input placeholder="请输入文件名称" />
          </Form.Item>
          <Form.Item name="visibility" label="权限设置"><Select options={visibilityOptions} /></Form.Item>
        </div>
        <Form.Item label="关联项目 / 阶段 / 任务">
          <ProjectRelationPicker value={relations} onChange={setRelations} multiple={false} />
        </Form.Item>
        <div className="eln-entry-label">记录方式</div>
        <div className="eln-entry-format-grid">
          <button type="button" className={recordMode === 'word' ? 'is-active' : ''} onClick={() => changeRecordMode('word')}><FileWordOutlined /><b>空白 Word<small>在线填写文档</small></b></button>
          <button type="button" className={recordMode === 'excel' ? 'is-active' : ''} onClick={() => changeRecordMode('excel')}><FileExcelOutlined /><b>空白 Excel<small>在线填写表格</small></b></button>
          <button type="button" className={recordMode === 'template' ? 'is-active' : ''} onClick={() => changeRecordMode('template')}><FileTextOutlined /><b>选择模板<small>按已发布模板创建</small></b></button>
        </div>
        {recordMode === 'template' && <Form.Item name="templateVersionId" label="选择模板" rules={[{ required: true, message: '请选择实验模板' }]}>
          <Select showSearch optionFilterProp="label" loading={templatesLoading} placeholder="请选择已发布模板" popupMatchSelectWidth={false} classNames={{ popup: { root: 'experiment-import-dropdown experiment-template-dropdown' } }} options={templateOptions} notFoundContent={templatesLoading ? '加载中' : '暂无已发布模板'} />
        </Form.Item>}
        <div className="eln-entry-submit"><Button type="primary" icon={<EditOutlined />} loading={submitting} onClick={() => void submit()}>创建并进入编辑</Button></div>
        {creationError && <Alert type="error" showIcon message="实验创建失败" description={creationError} />}
      </Form>
    </section>
    <section className="upload-workspace-panel upload-workspace-panel-right eln-entry-recent" aria-labelledby="recent-experiment-title">
      <div className="upload-workspace-right-heading">
        <div className="upload-workspace-section-title" id="recent-experiment-title">最近实验录入 <span>({recent.length})</span></div>
        <Input allowClear prefix={<SearchOutlined />} value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索实验编号 / 名称 / 实验人" />
      </div>
      <div className="eln-recent-list">
        {loadingRecent ? <Skeleton active paragraph={{ rows: 8 }} /> : filteredRecent.length ? filteredRecent.map((item) => <div className="eln-recent-item" key={item.id}>
          <span className="eln-recent-icon"><ExperimentOutlined /></span>
          <div><b>{item.title}</b><small>{item.experimentNo} · {item.categoryName || '未分类'}</small><small>{item.ownerName} · {item.experimentDate}{item.projectName ? ` · ${item.projectName}` : ''}</small></div>
          <Link to={`/experiments/${item.id}`} state={{ returnTo: '/experiments/new' }}>查看</Link>
        </div>) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无实验记录" />}
      </div>
    </section>
    </div>
  </div>;
}
