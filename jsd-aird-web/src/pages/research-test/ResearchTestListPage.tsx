import {
  App,
  Button,
  Card,
  DatePicker,
  Form,
  Input,
  List,
  Modal,
  Popconfirm,
  Segmented,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import {
  AppstoreOutlined,
  PlusOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { templateApi } from '@/services/templates/template-api';
import type { TemplateListItem } from '@/features/template-workspace/types';
import {
  copyResearchTest,
  createResearchTest,
  deleteResearchTest,
  downloadResearchTest,
  getResearchTest,
  listResearchTests,
  renameResearchTest,
  type ResearchTestSummary,
  type ResearchTestType,
} from '@/services/research-test/research-test-api';
import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';
import './research-test.css';
import '@/styles/management-list.css';

interface CreateFormValues {
  businessNo?: string;
  name: string;
  category?: string;
  scope?: string;
  ownerName?: string;
  date: dayjs.Dayjs;
  format: 'WORD' | 'EXCEL';
  sourceType: 'BLANK' | 'TEMPLATE';
  visibility: string;
  templateVersionId?: string;
  effectiveFrom?: dayjs.Dayjs;
  effectiveTo?: dayjs.Dayjs;
}

const statusText: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_REVIEW: '待审核',
  RETURNED: '已退回',
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
};
const standardCategories = ['国家标准', '行业标准', '企业标准', '客户标准', '内部测试方法'];
export function ResearchTestListPage({ type }: { type: ResearchTestType }) {
  const report = type === 'REPORT',
    nav = useNavigate(),
    { message } = App.useApp();
  const [rows, setRows] = useState<ResearchTestSummary[]>([]),
    [loading, setLoading] = useState(false),
    [total, setTotal] = useState(0),
    [page, setPage] = useState(1),
    [size, setSize] = useState(10);
  const [keyword, setKeyword] = useState(''),
    [projectId, setProjectId] = useState<string>(),
    [status, setStatus] = useState<string>(),
    [category, setCategory] = useState<string>(),
    [date, setDate] = useState<dayjs.Dayjs>(),
    [mode, setMode] = useState<'list' | 'card'>('list'),
    [open, setOpen] = useState(false),
    [submitting, setSubmitting] = useState(false),
    [templates, setTemplates] = useState<TemplateListItem[]>([]),
    [form] = Form.useForm<CreateFormValues>();
  const [renameOpen, setRenameOpen] = useState(false),
    [renameSaving, setRenameSaving] = useState(false),
    [renaming, setRenaming] = useState<ResearchTestSummary>(),
    [renameName, setRenameName] = useState(''),
    [renameRelations, setRenameRelations] = useState<ProjectRelationTarget[]>([]);
  const sourceType = Form.useWatch('sourceType', form);
  const documentFormat = Form.useWatch('format', form);
  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listResearchTests(type, {
        keyword,
        projectId,
        status,
        category,
        dateFrom: date?.format('YYYY-MM-DD'),
        dateTo: date?.format('YYYY-MM-DD'),
        page,
        size,
      });
      setRows(result.items);
      setTotal(result.total);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [category, date, keyword, message, page, projectId, size, status, type]);
  useEffect(() => {
    void load();
  }, [load]);
  const showCreate = () => {
    form.resetFields();
    setOpen(true);
    form.setFieldsValue({
      date: dayjs(),
      format: 'WORD',
      sourceType: 'BLANK',
      visibility: 'ALL',
      ownerName: '张三',
      businessNo: report ? undefined : 'STD-' + dayjs().format('YYYYMMDD') + '-001',
      effectiveFrom: report ? undefined : dayjs(),
    });
    void templateApi
      .list({ status: 'PUBLISHED', page: 1, size: 100 })
      .then((x) => setTemplates(x.items.filter((item) => matchesResearchTestTemplate(item, type))));
  };
  const submit = async () => {
    setSubmitting(true);
    try {
      const v = await form.validateFields();
      let templateSnapshot: Record<string, unknown> | undefined, templateHash: string | undefined;
      const selected = templates.find((x) => x.versionId === v.templateVersionId);
      if (v.sourceType === 'TEMPLATE') {
        if (!selected) throw new Error('请选择一个已发布模板');
        const w = await templateApi.getEditModel(selected.versionId);
        templateSnapshot =
          w.snapshotFileId && w.snapshotHash
            ? await templateApi.downloadSnapshot(w.snapshotFileId)
            : w.inlineSnapshot;
        templateHash = w.snapshotHash ?? w.workspaceHash;
        if (!templateSnapshot) throw new Error('所选模板没有可用的文档内容');
      }
      const format =
        selected?.format === 'XLSX' ? 'EXCEL' : selected?.format === 'DOCX' ? 'WORD' : v.format;
      const result = await createResearchTest(type, {
        ...v,
        date: report ? v.date?.format('YYYY-MM-DD') : dayjs().format('YYYY-MM-DD'),
        effectiveFrom: v.effectiveFrom?.format('YYYY-MM-DD'),
        effectiveTo: v.effectiveTo?.format('YYYY-MM-DD'),
        format,
        templateSnapshot,
        templateHash,
        editModel: {
          title: v.name,
          documentFormat: format.toLowerCase(),
          documentSnapshot: templateSnapshot,
        },
      });
      message.success(report ? '报告已创建' : '测试标准已创建');
      setOpen(false);
      nav(`/research-test/${report ? 'reports' : 'standards'}/${result.summary.id}`);
    } catch (error) {
      const validation = error as { errorFields?: Array<{ errors?: string[] }> };
      if (validation.errorFields?.length) {
        const firstError = validation.errorFields.flatMap((field) => field.errors || [])[0];
        message.warning(firstError || '请完善必填信息');
      } else {
        message.error(error instanceof Error ? error.message : '创建失败，请稍后重试');
      }
    } finally {
      setSubmitting(false);
    }
  };
  const remove = async (r: ResearchTestSummary) => {
    await deleteResearchTest(type, r.id, r.lockVersion);
    message.success('已删除');
    void load();
  };
  const copy = async (r: ResearchTestSummary) => {
    await copyResearchTest(type, r.id);
    message.success('已复制为草稿');
    void load();
  };
  const openRename = async (r: ResearchTestSummary) => {
    setRenaming(r);
    setRenameName(r.name);
    setRenameRelations(
      r.projectId
        ? [{
            projectId: r.projectId,
            projectName: r.projectName,
            stageId: r.stageId,
            stageName: r.stageName,
            taskId: r.taskId,
            taskName: r.taskName,
          }]
        : [],
    );
    setRenameOpen(true);
    try {
      const detail = await getResearchTest('REPORT', r.id);
      const summary = detail.summary;
      setRenaming(summary);
      setRenameName(summary.name);
      setRenameRelations(
        summary.projectId
          ? [{
              projectId: summary.projectId,
              projectName: summary.projectName,
              stageId: summary.stageId,
              stageName: summary.stageName,
              taskId: summary.taskId,
              taskName: summary.taskName,
            }]
          : [],
      );
    } catch (error) {
      message.error(error instanceof Error ? error.message : '报告信息加载失败');
      setRenameOpen(false);
    }
  };
  const saveRename = async () => {
    if (!renaming || !renameName.trim()) {
      message.warning('请输入报告名称');
      return;
    }
    const relation = renameRelations[0];
    setRenameSaving(true);
    try {
      await renameResearchTest('REPORT', renaming.id, {
        revision: renaming.lockVersion,
        name: renameName.trim(),
        projectId: relation?.projectId || undefined,
        stageId: relation?.stageId || undefined,
        taskId: relation?.taskId || undefined,
      });
      message.success('报告名称和项目关联已更新');
      setRenameOpen(false);
      setRenaming(undefined);
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '报告信息保存失败');
    } finally {
      setRenameSaving(false);
    }
  };
  const columns = useMemo(
    () => [
      { title: report ? '报告编号' : '标准编号', dataIndex: 'businessNo', width: 170 },
      {
        title: report ? '报告名称' : '标准名称',
        dataIndex: 'name',
        render: (v: string, r: ResearchTestSummary) => (
          <Button
            type="link"
            onClick={() => nav(`/research-test/${report ? 'reports' : 'standards'}/${r.id}`)}
          >
            {v}
          </Button>
        ),
      },
      {
        title: report ? '所属项目' : '标准类别',
        dataIndex: report ? 'projectName' : 'category',
        render: (v?: string) => v || '—',
      },
      ...(report
        ? [{ title: '负责人', dataIndex: 'ownerName' }]
        : [{ title: '适用对象', dataIndex: 'applicableScope' }]),
      {
        title: report ? '日期' : '发布日期',
        dataIndex: 'businessDate',
        render: (v?: string) => v || '—',
      },
      {
        title: '使用状态',
        dataIndex: 'status',
        render: (v: string) => (
          <Tag color={v === 'PUBLISHED' ? 'green' : v === 'PENDING_REVIEW' ? 'orange' : 'blue'}>
            {statusText[v] || v}
          </Tag>
        ),
      },
      {
        title: '操作',
        key: 'actions',
        width: 250,
        fixed: 'right' as const,
        render: (_: unknown, r: ResearchTestSummary) => (
          <Space size={0}>
            <Button
              type="link"
              size="small"
              onClick={() => nav(`/research-test/${report ? 'reports' : 'standards'}/${r.id}`)}
            >
              查看
            </Button>
            {report && (
              <Button type="link" size="small" onClick={() => void openRename(r)}>
                重命名
              </Button>
            )}
            <Button type="link" size="small" onClick={() => void copy(r)}>
              复制
            </Button>
            <Button
              type="link"
              size="small"
              onClick={() =>
                void downloadResearchTest(
                  report ? 'REPORT' : 'STANDARD',
                  r.id,
                  r.name,
                  r.documentFormat,
                ).catch((e) => message.error(e instanceof Error ? e.message : '下载失败'))
              }
            >
              下载
            </Button>
            <Popconfirm
              title="确认删除该草稿？"
              description="删除后该记录将从列表中移除。"
              okText="删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={() => void remove(r)}
            >
              <Button
                type="link"
                size="small"
                danger
                disabled={!['DRAFT', 'RETURNED'].includes(r.status)}
              >
                删除
              </Button>
            </Popconfirm>
          </Space>
        ),
      },
    ],
    [message, nav, openRename, report],
  );
  return (
    <section className="research-test-page pm-unified-list-page">
      <header className="research-test-head">
        <div>
          <Typography.Title level={3}>{report ? '综合测试报告' : '测试标准方法'}</Typography.Title>
          <Typography.Text type="secondary">统一管理测试文档、发布版本与审核同步。</Typography.Text>
        </div>
        {!report && (
          <Space>
            <Segmented
              value={mode}
              onChange={(v) => setMode(v as 'list' | 'card')}
              options={[
                { value: 'list', icon: <UnorderedListOutlined /> },
                { value: 'card', icon: <AppstoreOutlined /> },
              ]}
            />
            <Button type="primary" icon={<PlusOutlined />} onClick={showCreate}>
              新增测试标准
            </Button>
          </Space>
        )}
      </header>
      <Card className={report ? 'research-test-filter has-create-action' : 'research-test-filter'}>
        <Space wrap>
          <Input.Search
            allowClear
            placeholder={report ? '搜索报告编号／名称' : '搜索标准编号／名称'}
            style={{ width: 320 }}
            onSearch={(v) => {
              setKeyword(v);
              setPage(1);
            }}
          />
          <Select<string>
            allowClear
            placeholder={report ? '全部项目' : '全部标准类别'}
            style={{ width: 180 }}
            options={
              report
                ? Array.from(
                    new Map(
                      rows
                        .filter((x) => x.projectId && x.projectName)
                        .map((x) => [x.projectId as string, x.projectName as string]),
                    ),
                  ).map(([value, label]) => ({ value, label }))
                : standardCategories.map((v) => ({ value: v, label: v }))
            }
            onChange={(v) => {
              if (report) setProjectId(v);
              else setCategory(v);
              setPage(1);
            }}
          />
          <Select<string>
            allowClear
            placeholder="全部使用状态"
            style={{ width: 160 }}
            options={Object.entries(statusText).map(([value, label]) => ({ value, label }))}
            onChange={(v) => {
              setStatus(v);
              setPage(1);
            }}
          />
          <DatePicker
            value={date}
            onChange={(v) => {
              setDate(v);
              setPage(1);
            }}
          />
          <Button
            onClick={() => {
              setKeyword('');
              setProjectId(undefined);
              setCategory(undefined);
              setStatus(undefined);
              setDate(undefined);
              setPage(1);
            }}
          >
            重置
          </Button>
          {report && (
            <Button type="primary" icon={<PlusOutlined />} onClick={showCreate}>
              新增报告
            </Button>
          )}
        </Space>
      </Card>
      {!report && mode === 'card' ? (
        <List
          loading={loading}
          grid={{ gutter: 16, xs: 1, sm: 1, md: 2, lg: 3 }}
          dataSource={rows}
          pagination={{ current: page, pageSize: size, total, onChange: setPage }}
          renderItem={(r) => (
            <List.Item>
              <Card
                title={r.name}
                extra={<Tag>{statusText[r.status]}</Tag>}
                actions={[
                  <Button
                    type="link"
                    onClick={() =>
                      nav(`/research-test/${report ? 'reports' : 'standards'}/${r.id}`)
                    }
                  >
                    查看
                  </Button>,
                  ...(report
                    ? [
                        <Button type="link" onClick={() => void openRename(r)}>
                          重命名
                        </Button>,
                      ]
                    : []),
                  <Button type="link" onClick={() => void copy(r)}>
                    复制
                  </Button>,
                ]}
              >
                <p>{r.businessNo}</p>
                <p>{report ? r.projectName || '未关联项目' : r.category || '未分类'}</p>
                <Typography.Text type="secondary">
                  {['PUBLISHED', 'ARCHIVED'].includes(r.status) ? `V${r.versionNo}` : '未发布'} ·{' '}
                  {r.ownerName || '—'}
                </Typography.Text>
              </Card>
            </List.Item>
          )}
        />
      ) : (
        <div className="research-test-table-wrap">
          <Table
            rowKey="id"
            loading={loading}
            dataSource={rows}
            columns={columns}
            scroll={{ x: 'max-content' }}
            pagination={{
              current: page,
              pageSize: size,
              total,
              showSizeChanger: true,
              onChange: (p, s) => {
                setPage(p);
                setSize(s);
              },
            }}
          />
        </div>
      )}
      <Modal
        rootClassName="research-test-create-modal"
        open={open}
        title={report ? '新增报告' : '新增测试标准'}
        width={report ? 760 : 598}
        okText="创建并进入编辑"
        confirmLoading={submitting}
        okButtonProps={{ disabled: submitting }}
        onCancel={() => setOpen(false)}
        onOk={() => void submit()}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="sourceType" hidden rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="format" hidden rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <div className={report ? 'research-test-form-grid' : 'research-test-create-grid'}>
            <Form.Item
              name="businessNo"
              label={report ? '报告编号' : '标准编号'}
              rules={report ? [] : [{ required: true }]}
            >
              <Input placeholder={report ? '系统自动生成' : '请输入标准编号'} />
            </Form.Item>
            <Form.Item
              name="name"
              label={report ? '报告名称' : '标准名称'}
              rules={[{ required: true }]}
            >
              <Input />
            </Form.Item>
            {report ? (
              <>
                <Form.Item name="ownerName" label="负责人" rules={[{ required: true }]}>
                  <Input />
                </Form.Item>
                <Form.Item name="date" label="日期" rules={[{ required: true }]}>
                  <DatePicker style={{ width: '100%' }} />
                </Form.Item>
              </>
            ) : (
              <>
                <Form.Item name="category" label="标准类别" rules={[{ required: true }]}>
                  <Select options={standardCategories.map((value) => ({ value, label: value }))} />
                </Form.Item>
                <Form.Item name="scope" label="适用对象">
                  <Input />
                </Form.Item>
                <Form.Item name="effectiveFrom" label="生效日期" rules={[{ required: true }]}>
                  <DatePicker style={{ width: '100%' }} />
                </Form.Item>
                <Form.Item name="effectiveTo" label="失效日期">
                  <DatePicker style={{ width: '100%' }} />
                </Form.Item>
              </>
            )}
          </div>
          <CreateChoice
            label="选择新建"
            value={sourceType || 'BLANK'}
            options={[
              {
                key: 'BLANK',
                title: '空白新建',
                desc: `选择 Word 或 Excel 创建空白${report ? '报告' : '测试标准'}`,
              },
              {
                key: 'TEMPLATE',
                title: '选择模板新建',
                desc: `复制已发布模板形成独立${report ? '报告' : '测试标准'}副本`,
              },
            ]}
            onChange={(value) => {
              form.setFieldValue('sourceType', value);
              form.setFieldValue('templateVersionId', undefined);
            }}
          />
          {sourceType === 'TEMPLATE' ? (
            <PublishedTemplatePicker
              templates={templates}
              documentName={report ? '报告' : '测试标准'}
            />
          ) : (
            <CreateChoice
              label="文档格式"
              value={documentFormat || 'WORD'}
              options={[
                {
                  key: 'WORD',
                  title: `Word ${report ? '报告' : '测试标准'}`,
                  desc: '正文、章节与文档结构编辑',
                },
                {
                  key: 'EXCEL',
                  title: `Excel ${report ? '报告' : '测试标准'}`,
                  desc: '表格、单元格与字段结构编辑',
                },
              ]}
              onChange={(value) => form.setFieldValue('format', value)}
            />
          )}
        </Form>
      </Modal>
      {report && (
        <Modal
          rootClassName="research-test-create-modal"
          open={renameOpen}
          title="重命名并关联项目"
          width={720}
          confirmLoading={renameSaving}
          okText="保存"
          cancelText="取消"
          onCancel={() => setRenameOpen(false)}
          onOk={() => void saveRename()}
        >
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <div>
              <Typography.Text strong>报告名称</Typography.Text>
              <Input
                value={renameName}
                maxLength={300}
                placeholder="请输入报告名称"
                onChange={(event) => setRenameName(event.target.value)}
                style={{ marginTop: 8 }}
              />
            </div>
            <div>
              <Typography.Text strong>关联项目 / 阶段 / 任务</Typography.Text>
              <div style={{ marginTop: 8 }}>
                <ProjectRelationPicker
                  value={renameRelations}
                  onChange={setRenameRelations}
                  multiple={false}
                />
              </div>
            </div>
          </Space>
        </Modal>
      )}
    </section>
  );
}

function CreateChoice({
  label,
  options,
  value,
  onChange,
}: {
  label: string;
  options: Array<{ key: string; title: string; desc: string }>;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <div className="research-test-choice-section">
      <label>
        <i>*</i>
        {label}
      </label>
      <div>
        {options.map((option, index) => (
          <button
            type="button"
            className={value === option.key ? 'active' : ''}
            onClick={() => onChange(option.key)}
            key={option.key}
          >
            <span>{index === 0 ? '▣' : '↪'}</span>
            <b>
              {option.title}
              <small>{option.desc}</small>
            </b>
          </button>
        ))}
      </div>
    </div>
  );
}

function PublishedTemplatePicker({
  templates,
  documentName,
}: {
  templates: TemplateListItem[];
  documentName: string;
}) {
  const [categoryId, setCategoryId] = useState<string>();
  const categories = useMemo(() => {
    const values = new Map<string, string>();
    templates.forEach((item) =>
      values.set(item.categoryId ?? '__UNCATEGORIZED__', item.category || '未分类'),
    );
    return [...values.entries()].map(([value, label]) => ({ value, label }));
  }, [templates]);
  const filtered = categoryId
    ? templates.filter((item) => (item.categoryId ?? '__UNCATEGORIZED__') === categoryId)
    : templates;
  return (
    <>
      <Form.Item label="模板分类">
        <Select
          allowClear
          placeholder="全部模板分类"
          options={categories}
          value={categoryId}
          onChange={setCategoryId}
        />
      </Form.Item>
      <Form.Item
        name="templateVersionId"
        label="选择模板"
        rules={[{ required: true, message: `请选择${documentName}模板` }]}
      >
        <Select
          showSearch
          optionFilterProp="label"
          placeholder="请选择已发布模板"
          options={filtered.map((item) => ({
            value: item.versionId,
            label: `${item.name}（${item.format === 'XLSX' ? 'Excel' : 'Word'} · V${item.versionNo}）`,
          }))}
          notFoundContent="暂无该分类的已发布模板"
        />
      </Form.Item>
    </>
  );
}

function matchesResearchTestTemplate(item: TemplateListItem, type: ResearchTestType) {
  const searchable = `${item.name} ${item.category || ''}`.toLowerCase();
  if (searchable.includes('实验')) return false;
  return type === 'STANDARD'
    ? /(测试|检测|检验|标准|方法)/.test(searchable)
    : /(测试|检测|检验|报告)/.test(searchable);
}
