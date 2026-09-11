import {
  AppstoreOutlined,
  CopyOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  PlusOutlined,
  RedoOutlined,
  UnorderedListOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import {
  App,
  Button,
  Breadcrumb,
  Card,
  Checkbox,
  Empty,
  Form,
  Input,
  Modal,
  Pagination,
  Select,
  Space,
  Spin,
  Table,
  Typography,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import type { ProjectRelationTarget } from '@/services/project/project-resource-api';
import {
  getProjects,
  getProjectStages,
  getStageTasks,
  type Project,
  type ProjectStage,
  type ProjectTask,
} from '@/services/project/project-api';
import { usePermission } from '@/components/auth/usePermission';
import { downloadBlob, stageFile } from '@/services/files/file-api';
import { templateApi } from '@/services/templates/template-api';
import {
  productionUploadApi,
  productionOrderRecordApi,
  type ProductionUpload,
} from '@/services/production-orders/production-upload-api';
import './production-order-list.css';
import '@/styles/management-list.css';

const statusOptions = [
  { value: 'SAVED', label: '已保存' },
  { value: 'PUBLISHED', label: '已发布' },
];

type ViewMode = 'card' | 'list';

const renderCellText = (value?: string | null, strong = false) => {
  const text = value || '—';
  return (
    <Typography.Text
      className="production-order-cell-text"
      strong={strong}
      ellipsis={{ tooltip: text }}
    >
      {text}
    </Typography.Text>
  );
};

export function ProductionOrderListPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const canCreate = usePermission('production.create');
  const canUpdate = usePermission('production.update');
  const canDelete = usePermission('production.delete');
  const [items, setItems] = useState<ProductionUpload[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<string>();
  const [page, setPage] = useState({ current: 1, pageSize: 10, total: 0 });
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const [view, setView] = useState<ViewMode>('list');
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [copying, setCopying] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [renameOpen, setRenameOpen] = useState(false);
  const [renameSaving, setRenameSaving] = useState(false);
  const [renaming, setRenaming] = useState<ProductionUpload>();
  const [renameRelations, setRenameRelations] = useState<ProjectRelationTarget[]>([]);
  const [createRelations, setCreateRelations] = useState<ProjectRelationTarget[]>([]);
  const [createMode, setCreateMode] = useState<'BLANK' | 'TEMPLATE'>('BLANK');
  const [createFormat, setCreateFormat] = useState<'WORD' | 'XLSX'>('XLSX');
  const [createTemplateVersionId, setCreateTemplateVersionId] = useState<string>();
  const [createTemplates, setCreateTemplates] = useState<
    Array<{ versionId: string; templateCode: string; name: string; versionNo: number }>
  >([]);
  const [createTemplatesLoading, setCreateTemplatesLoading] = useState(false);
  const [createForm] = Form.useForm<{
    productionName: string;
    orderNo: string;
    productName: string;
    category: string;
    manufactureDate: string;
  }>();
  const [renameForm] = Form.useForm<{
    productionName: string;
    orderNo: string;
    productName: string;
    category: string;
    manufactureDate: string;
  }>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await productionUploadApi.list({
        keyword: keyword || undefined,
        status: status || undefined,
        viewableOnly: true,
        page: page.current,
        size: page.pageSize,
      });
      setItems(result.items);
      setPage((current) => ({
        ...current,
        current: result.page,
        pageSize: result.size,
        total: result.total,
      }));
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '生产单列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [keyword, message, page.current, page.pageSize, status]);

  useEffect(() => {
    void load();
  }, [load]);

  const deleteSelected = () => {
    if (!selectedKeys.length) return;
    Modal.confirm({
      title: `确认删除 ${selectedKeys.length} 条生产单记录？`,
      content: '删除后记录会进入作废状态，原始文件不会被物理删除。',
      okText: '确认删除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await Promise.all(selectedKeys.map((id) => productionUploadApi.delete(id)));
          setSelectedKeys([]);
          void message.success('选中的生产单记录已作废');
          await load();
        } catch (error) {
          void message.error(error instanceof Error ? error.message : '批量删除失败');
        }
      },
    });
  };

  const selectedItems = items.filter((item) => selectedKeys.includes(item.id));

  const renameSelected = () => {
    if (selectedItems.length !== 1) {
      void message.warning('请只选择一条生产单后重命名');
      return;
    }
    openRename(selectedItems[0]!);
  };

  const exportSelected = async () => {
    if (!selectedItems.length) return;
    setExporting(true);
    try {
      await Promise.all(
        selectedItems.map(async (item) => {
          const blob = await productionOrderRecordApi.export(item.id);
          downloadBlob(blob, item.originalName || `${item.productionName || '生产单'}.xlsx`);
        }),
      );
      void message.success(`已导出 ${selectedItems.length} 条生产单`);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '生产单导出失败');
    } finally {
      setExporting(false);
    }
  };

  const copySelected = async () => {
    if (!selectedItems.length) return;
    const unsupported = selectedItems.filter((item) => !/\.xlsx$/i.test(item.originalName));
    if (unsupported.length) {
      void message.warning('当前仅支持复制 Excel 生产单');
      return;
    }
    setCopying(true);
    try {
      const XLSX = await import('xlsx');
      const copies = await Promise.all(
        selectedItems.map(async (item) => {
          const source = await productionOrderRecordApi.export(item.id);
          const workbook = XLSX.read(await source.arrayBuffer(), { type: 'array' });
          workbook.Props = {
            ...workbook.Props,
            Subject: `生产单副本-${crypto.randomUUID()}`,
          };
          const content = XLSX.write(workbook, {
            bookType: 'xlsx',
            type: 'array',
          }) as ArrayBuffer;
          const productionName = `${item.productionName || item.originalName.replace(/\.xlsx$/i, '')} - 副本`;
          const staged = await stageFile(
            new File([content], `${productionName}.xlsx`, {
              type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            }),
            'PRODUCTION_SOURCE',
          );
          return productionUploadApi.create({
            fileId: staged.fileId,
            sourceType: 'XLSX',
            productionName,
            orderNo: item.orderNo,
            productName: item.productName,
            category: item.category,
            manufactureDate: item.manufactureDate,
            projectId: item.projectId,
            projectName: item.projectName,
            stageId: item.stageId,
            stageName: item.stageName,
            taskId: item.taskId,
            taskName: item.taskName,
            visibility: item.visibility,
          });
        }),
      );
      for (const copy of copies) {
        for (let attempt = 0; attempt < 30; attempt += 1) {
          const current = await productionUploadApi.get(copy.id);
          if (['SAVED', 'PUBLISHED', 'REVIEW_REQUIRED', 'FAILED'].includes(current.status)) break;
          await new Promise((resolve) => window.setTimeout(resolve, 500));
        }
      }
      setSelectedKeys([]);
      await load();
      void message.success(`已复制 ${copies.length} 条生产单`);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '生产单复制失败');
    } finally {
      setCopying(false);
    }
  };

  const deleteItem = (item: ProductionUpload) => {
    Modal.confirm({
      title: `确认删除生产单“${item.productionName || item.orderNo || item.originalName}”？`,
      content: '删除后记录会进入作废状态，原始文件不会被物理删除。',
      okText: '删除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await productionUploadApi.delete(item.id);
          setSelectedKeys((current) => current.filter((id) => id !== item.id));
          void message.success('生产单记录已作废');
          await load();
        } catch (error) {
          void message.error(error instanceof Error ? error.message : '删除失败');
        }
      },
    });
  };

  const openRename = (item: ProductionUpload) => {
    setRenaming(item);
    renameForm.setFieldsValue({
      productionName: item.productionName || item.orderNo || item.originalName,
      orderNo: item.orderNo || '',
      productName: item.productName || '',
      category: item.category || '',
      manufactureDate: item.manufactureDate || '',
    });
    setRenameRelations(
      item.projectId
        ? [
            {
              projectId: item.projectId,
              projectName: item.projectName,
              stageId: item.stageId,
              stageName: item.stageName,
              taskId: item.taskId,
              taskName: item.taskName,
            },
          ]
        : [{ projectId: '' }],
    );
    setRenameOpen(true);
  };

  const saveRename = async () => {
    try {
      if (!renaming) return;
      const values = await renameForm.validateFields();
      const relation = renameRelations[0];
      setRenameSaving(true);
      await productionOrderRecordApi.saveBatch({
        records: [
          {
            id: renaming.id,
            lockVersion: renaming.lockVersion,
            productionName: values.productionName.trim(),
            orderNo: values.orderNo.trim(),
            productName: values.productName.trim(),
            category: values.category.trim(),
            manufactureDate: values.manufactureDate,
          },
        ],
      });
      const updated = await productionOrderRecordApi.get(renaming.id);
      await productionUploadApi.rename(renaming.id, {
        revision: updated.lockVersion,
        name: values.productionName.trim(),
        projectId: relation?.projectId,
        projectName: relation?.projectName,
        stageId: relation?.stageId,
        stageName: relation?.stageName,
        taskId: relation?.taskId,
        taskName: relation?.taskName,
      });
      message.success('生产单信息已更新');
      setRenameOpen(false);
      setRenaming(undefined);
      renameForm.resetFields();
      await load();
    } catch (error) {
      if (error && typeof error === 'object' && 'errorFields' in error) return;
      message.error(error instanceof Error ? error.message : '生产单信息保存失败');
    } finally {
      setRenameSaving(false);
    }
  };

  const openCreate = () => {
    createForm.resetFields();
    setCreateRelations([{ projectId: '' }]);
    setCreateMode('BLANK');
    setCreateFormat('XLSX');
    setCreateTemplateVersionId(undefined);
    setCreateOpen(true);
  };

  const selectCreateMode = async (value: 'BLANK' | 'TEMPLATE') => {
    setCreateMode(value);
    setCreateFormat('XLSX');
    setCreateTemplateVersionId(undefined);
    if (value !== 'TEMPLATE' || createTemplates.length) return;
    setCreateTemplatesLoading(true);
    try {
      const result = await templateApi.list({
        format: 'XLSX',
        status: 'PUBLISHED',
        page: 1,
        size: 100,
      });
      setCreateTemplates(
        result.items.map((item) => ({
          versionId: item.currentPublishedVersionId ?? item.versionId,
          templateCode: item.templateCode,
          name: item.name,
          versionNo: item.currentPublishedVersionNo ?? item.versionNo,
        })),
      );
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '已发布模板加载失败');
    } finally {
      setCreateTemplatesLoading(false);
    }
  };

  const createOrder = async () => {
    try {
      const values = await createForm.validateFields();
      if (createMode === 'BLANK' && createFormat === 'WORD') {
        message.warning('生产单工作区当前仅支持 Excel 格式');
        return;
      }
      if (createMode === 'TEMPLATE' && !createTemplateVersionId) {
        message.warning('请选择已发布的 XLSX 模板');
        return;
      }
      setCreating(true);
      const XLSX = await import('xlsx');
      const workbook = XLSX.utils.book_new();
      const worksheet = XLSX.utils.aoa_to_sheet([
        ['生产单名称', values.productionName],
        ['订单号', values.orderNo],
        ['品名', values.productName],
        ['类别', values.category],
        ['制造日期', values.manufactureDate],
      ]);
      worksheet['!cols'] = [{ wch: 16 }, { wch: 40 }];
      XLSX.utils.book_append_sheet(workbook, worksheet, '生产单');
      const content = XLSX.write(workbook, {
        bookType: 'xlsx',
        type: 'array',
      }) as ArrayBuffer;
      const safeName = values.productionName.replace(/[\\/:*?"<>|]/g, '_').trim() || '生产单';
      const file = new File([content], `${safeName}.xlsx`, {
        type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      });
      const staged = await stageFile(file, 'PRODUCTION_SOURCE');
      const relation = createRelations[0];
      const created = await productionUploadApi.create({
        fileId: staged.fileId,
        sourceType: 'XLSX',
        templateVersionId: createTemplateVersionId,
        productionName: values.productionName.trim(),
        orderNo: values.orderNo.trim(),
        productName: values.productName.trim(),
        category: values.category.trim(),
        manufactureDate: values.manufactureDate,
        projectId: relation?.projectId,
        projectName: relation?.projectName,
        stageId: relation?.stageId,
        stageName: relation?.stageName,
        taskId: relation?.taskId,
        taskName: relation?.taskName,
        visibility: 'ALL',
      });
      setCreateOpen(false);
      createForm.resetFields();
      setCreateRelations([{ projectId: '' }]);
      setCreateMode('BLANK');
      setCreateFormat('XLSX');
      setCreateTemplateVersionId(undefined);
      void message.success('生产单已创建，正在生成预览');
      let ready = created;
      for (let attempt = 0; attempt < 30; attempt += 1) {
        if (['SAVED', 'PUBLISHED', 'REVIEW_REQUIRED'].includes(ready.status)) break;
        if (ready.status === 'FAILED') {
          throw new Error(ready.failureMessage || '生产单预览生成失败');
        }
        await new Promise((resolve) => window.setTimeout(resolve, 500));
        ready = await productionUploadApi.get(created.id);
      }
      await load();
      if (['SAVED', 'PUBLISHED', 'REVIEW_REQUIRED'].includes(ready.status)) {
        navigate(`/production-orders/uploads/${created.id}/workspace`);
      } else {
        void message.info('生产单仍在后台生成，可稍后从上传页面查看进度');
        navigate('/production-orders/upload');
      }
    } catch (error) {
      if (error && typeof error === 'object' && 'errorFields' in error) return;
      void message.error(error instanceof Error ? error.message : '生产单创建失败');
    } finally {
      setCreating(false);
    }
  };

  const resetFilters = () => {
    setKeyword('');
    setStatus(undefined);
    setPage((current) => ({ ...current, current: 1 }));
  };

  const changeView = (nextView: ViewMode) => {
    if (nextView !== view) setSelectedKeys([]);
    setView(nextView);
  };

  const toggleSelected = (id: string) => {
    setSelectedKeys((current) =>
      current.includes(id) ? current.filter((key) => key !== id) : [...current, id],
    );
  };

  const openWorkspace = (id: string) => {
    navigate(`/production-orders/uploads/${id}/workspace`);
  };

  const renderProductionCard = (item: ProductionUpload) => {
    const relation =
      [item.projectName, item.stageName, item.taskName].filter(Boolean).join(' · ') || '未关联项目';
    return (
      <article className="production-order-card" key={item.id}>
        <div className="production-order-card-head">
          <div className="production-order-card-title">
            <Typography.Link
              strong
              className="production-order-card-name-link"
              ellipsis
              title={item.productionName || item.orderNo || '未命名生产单'}
              onClick={() => openWorkspace(item.id)}
            >
              {item.productionName || item.orderNo || '未命名生产单'}
            </Typography.Link>
          </div>
          <Checkbox
            aria-label={`选择生产单 ${item.productionName || item.orderNo || '未命名生产单'}`}
            checked={selectedKeys.includes(item.id)}
            onChange={() => toggleSelected(item.id)}
          />
        </div>
        <div className="production-order-card-fields">
          <div>
            <span>订单号</span>
            <Typography.Text ellipsis={{ tooltip: item.orderNo || '—' }}>
              {item.orderNo || '—'}
            </Typography.Text>
          </div>
          <div>
            <span>品名</span>
            <Typography.Text ellipsis={{ tooltip: item.productName || '—' }}>
              {item.productName || '—'}
            </Typography.Text>
          </div>
          <div>
            <span>类别</span>
            <Typography.Text ellipsis={{ tooltip: item.category || '—' }}>
              {item.category || '—'}
            </Typography.Text>
          </div>
          <div>
            <span>制造日期</span>
            <Typography.Text>{item.manufactureDate || '—'}</Typography.Text>
          </div>
          <div className="production-order-card-field-wide">
            <span>关联项目 / 阶段 / 任务</span>
            <Typography.Text ellipsis={{ tooltip: relation }}>{relation}</Typography.Text>
          </div>
        </div>
        <div className="production-order-card-meta">
          上传于 {new Date(item.createdAt).toLocaleString('zh-CN')}
        </div>
        <div className="production-order-card-actions">
          <Button type="link" onClick={() => openWorkspace(item.id)}>
            查看
          </Button>
          <Button type="link" disabled={!canUpdate} onClick={() => openRename(item)}>
            重命名
          </Button>
          <Button type="link" danger disabled={!canDelete} onClick={() => deleteItem(item)}>
            删除
          </Button>
        </div>
      </article>
    );
  };

  return (
    <>
      <div className="business-page production-order-page pm-unified-list-page">
        <div className="page-heading production-order-page-intro">
          <div>
            <Breadcrumb items={[{ title: '生产单管理' }, { title: '生产单查看' }]} />
            <Typography.Title level={2}>生产单查看</Typography.Title>
            <Typography.Text type="secondary">
              查看已上传的生产单资料，当前不进入模板填写或提交流程。
            </Typography.Text>
          </div>
        </div>

        <Card className="content-card filter-card production-order-filter">
          <Space wrap>
            <Input.Search
              allowClear
              placeholder="生产单名称、订单号、品名或文件名"
              value={keyword}
              onChange={(event) => {
                setKeyword(event.target.value);
                setPage((current) => ({ ...current, current: 1 }));
              }}
              style={{ width: 'min(300px, 100%)' }}
            />
            <Select
              allowClear
              placeholder="全部状态"
              value={status}
              onChange={(value) => {
                setStatus(value);
                setPage((current) => ({ ...current, current: 1 }));
              }}
              options={statusOptions}
              style={{ width: 'min(140px, 100%)' }}
            />
            <Button icon={<RedoOutlined />} onClick={resetFilters}>
              重置
            </Button>
          </Space>
        </Card>

        <div className="production-order-toolbar pm-batch-row">
          <div className="pm-batch-summary">
            <span className="pm-selected">已选 {selectedKeys.length} 条</span>
            {selectedKeys.length > 0 && (
              <Button type="link" size="small" onClick={() => setSelectedKeys([])}>
                清除已选
              </Button>
            )}
          </div>
          <div className="pm-batch-actions">
            <Button
              icon={<CopyOutlined />}
              disabled={!selectedKeys.length}
              loading={copying}
              onClick={() => void copySelected()}
            >
              复制
            </Button>
            <Button icon={<EditOutlined />} onClick={renameSelected}>
              重命名
            </Button>
            <Button
              icon={<DownloadOutlined />}
              disabled={!selectedKeys.length}
              loading={exporting}
              onClick={() => void exportSelected()}
            >
              导出
            </Button>
            <Button
              danger
              icon={<DeleteOutlined />}
              disabled={!selectedKeys.length || !canDelete}
              onClick={deleteSelected}
            >
              删除
            </Button>
            <Button
              icon={<UploadOutlined />}
              disabled={!canCreate}
              onClick={() => navigate('/production-orders/upload')}
            >
              上传生产单
            </Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              disabled={!canCreate}
              onClick={openCreate}
            >
              新建生产单
            </Button>
            <Space.Compact className="production-order-view-toggle">
              <Button
                aria-label="卡片视图"
                aria-pressed={view === 'card'}
                title="卡片视图"
                type={view === 'card' ? 'primary' : 'default'}
                icon={<AppstoreOutlined />}
                onClick={() => changeView('card')}
              />
              <Button
                aria-label="列表视图"
                aria-pressed={view === 'list'}
                title="列表视图"
                type={view === 'list' ? 'primary' : 'default'}
                icon={<UnorderedListOutlined />}
                onClick={() => changeView('list')}
              />
            </Space.Compact>
          </div>
        </div>

        {view === 'card' ? (
          <Card className="content-card production-order-card-view">
            {loading ? (
              <div className="production-order-card-loading">
                <Spin />
              </div>
            ) : items.length ? (
              <div className="production-order-card-grid">{items.map(renderProductionCard)}</div>
            ) : (
              <Empty description="暂无已保存的生产单" />
            )}
            {!loading && items.length > 0 && (
              <Pagination
                className="production-order-card-pagination"
                current={page.current}
                pageSize={page.pageSize}
                total={page.total}
                showSizeChanger
                showTotal={(total) => `共 ${total} 条记录`}
                onChange={(current, pageSize) =>
                  setPage((value) => ({ ...value, current, pageSize }))
                }
              />
            )}
          </Card>
        ) : (
          <Card
            className="content-card production-order-table-card"
            styles={{ body: { padding: 0 } }}
          >
            <Table
              rowKey="id"
              rowSelection={{
                selectedRowKeys: selectedKeys,
                onChange: (keys) => setSelectedKeys(keys.map(String)),
              }}
              loading={loading}
              dataSource={items}
              locale={{ emptyText: <Empty description="暂无已保存的生产单" /> }}
              tableLayout="fixed"
              pagination={{
                current: page.current,
                pageSize: page.pageSize,
                total: page.total,
                showSizeChanger: true,
                showTotal: (total) => `共 ${total} 条记录`,
                onChange: (current, pageSize) =>
                  setPage((value) => ({ ...value, current, pageSize })),
              }}
              columns={[
                {
                  title: '生产单名称',
                  dataIndex: 'productionName',
                  width: 280,
                  render: (value: string, item: ProductionUpload) => (
                    <Typography.Link
                      className="production-order-cell-text production-order-name-link"
                      strong
                      ellipsis
                      title={value || '—'}
                      onClick={() => openWorkspace(item.id)}
                    >
                      {value || '—'}
                    </Typography.Link>
                  ),
                },
                {
                  title: '订单号',
                  dataIndex: 'orderNo',
                  width: 135,
                  render: (value: string) => renderCellText(value),
                },
                {
                  title: '品名',
                  dataIndex: 'productName',
                  width: 180,
                  render: (value: string) => renderCellText(value),
                },
                {
                  title: '类别',
                  dataIndex: 'category',
                  width: 135,
                  render: (value: string) => renderCellText(value),
                },
                {
                  title: '制造日期',
                  dataIndex: 'manufactureDate',
                  width: 135,
                  render: (value: string) => renderCellText(value),
                },
                {
                  title: '关联项目 / 阶段 / 任务',
                  width: 320,
                  render: (_: unknown, item: ProductionUpload) =>
                    renderCellText(
                      [item.projectName, item.stageName, item.taskName]
                        .filter(Boolean)
                        .join(' · ') || '未关联项目',
                    ),
                },
                {
                  title: '上传时间',
                  width: 170,
                  render: (_: unknown, item: ProductionUpload) =>
                    renderCellText(new Date(item.createdAt).toLocaleString('zh-CN')),
                },
                {
                  title: '操作',
                  width: 150,
                  render: (_: unknown, item: ProductionUpload) => (
                    <Space className="management-table-actions" size={0}>
                      <Button type="link" size="small" onClick={() => openWorkspace(item.id)}>
                        查看
                      </Button>
                      <Button
                        type="link"
                        size="small"
                        disabled={!canUpdate}
                        onClick={() => openRename(item)}
                      >
                        重命名
                      </Button>
                      <Button
                        type="link"
                        size="small"
                        danger
                        disabled={!canDelete}
                        onClick={() => deleteItem(item)}
                      >
                        删除
                      </Button>
                    </Space>
                  ),
                },
              ]}
              scroll={{ x: 'max-content' }}
            />
          </Card>
        )}
      </div>
      <Modal
        rootClassName="production-order-create-modal"
        title="新建生产单"
        open={createOpen}
        confirmLoading={creating}
        okText="创建并进入工作区"
        cancelText="取消"
        onOk={() => void createOrder()}
        onCancel={() => {
          setCreateOpen(false);
          createForm.resetFields();
          setCreateRelations([{ projectId: '' }]);
          setCreateMode('BLANK');
          setCreateFormat('XLSX');
          setCreateTemplateVersionId(undefined);
        }}
        destroyOnHidden
        width={598}
      >
        <Form form={createForm} layout="vertical">
          <div className="production-order-create-grid">
            <Form.Item
              name="productionName"
              label="生产单名称"
              rules={[{ required: true, whitespace: true, message: '请输入生产单名称' }]}
            >
              <Input placeholder="请输入生产单名称" maxLength={200} />
            </Form.Item>
            <Form.Item
              name="orderNo"
              label="订单号"
              rules={[{ required: true, whitespace: true, message: '请输入订单号' }]}
            >
              <Input placeholder="请输入订单号" maxLength={120} />
            </Form.Item>
            <Form.Item
              name="productName"
              label="品名"
              rules={[{ required: true, whitespace: true, message: '请输入品名' }]}
            >
              <Input placeholder="请输入品名" maxLength={200} />
            </Form.Item>
            <Form.Item
              name="category"
              label="类别"
              rules={[{ required: true, whitespace: true, message: '请输入类别' }]}
            >
              <Input placeholder="请输入类别" maxLength={100} />
            </Form.Item>
            <Form.Item
              name="manufactureDate"
              label="制造日期"
              rules={[{ required: true, message: '请选择制造日期' }]}
            >
              <Input type="date" />
            </Form.Item>
            <Form.Item className="production-order-relation-field">
              <ProductionRelationFields value={createRelations} onChange={setCreateRelations} />
            </Form.Item>
          </div>
          <ProductionCreateChoice
            label="选择新建"
            value={createMode}
            options={[
              { key: 'BLANK', title: '空白新建', desc: '创建空白生产单工作区' },
              { key: 'TEMPLATE', title: '选择模板新建', desc: '基于已发布模板创建生产单副本' },
            ]}
            onChange={(value) => void selectCreateMode(value as 'BLANK' | 'TEMPLATE')}
          />
          {createMode === 'TEMPLATE' && (
            <Form.Item
              label="业务模板"
              required
              validateStatus={
                !createTemplateVersionId && createTemplates.length === 0 ? 'warning' : undefined
              }
              help={
                createTemplates.length === 0 && !createTemplatesLoading
                  ? '请选择已发布的 XLSX 模板'
                  : undefined
              }
            >
              <Select
                showSearch
                optionFilterProp="label"
                loading={createTemplatesLoading}
                value={createTemplateVersionId}
                placeholder="选择已发布模板"
                onChange={setCreateTemplateVersionId}
                options={createTemplates.map((item) => ({
                  value: item.versionId,
                  label: `${item.templateCode} · ${item.name} · V${item.versionNo}`,
                }))}
              />
            </Form.Item>
          )}
          {createMode === 'BLANK' && (
            <ProductionCreateChoice
              label="文档格式"
              value={createFormat}
              options={[
                { key: 'WORD', title: 'Word 生产单', desc: '正文、章节与文档结构编辑' },
                { key: 'XLSX', title: 'Excel 生产单', desc: '表格、单元格与字段结构编辑' },
              ]}
              onChange={(value) => setCreateFormat(value as 'WORD' | 'XLSX')}
            />
          )}
        </Form>
      </Modal>
      <Modal
        open={renameOpen}
        title="编辑生产单信息"
        width={720}
        confirmLoading={renameSaving}
        okText="保存"
        cancelText="取消"
        onCancel={() => {
          setRenameOpen(false);
          setRenaming(undefined);
          renameForm.resetFields();
          setRenameRelations([]);
        }}
        onOk={() => void saveRename()}
      >
        <Form form={renameForm} layout="vertical">
          <div className="production-order-create-grid">
            <Form.Item
              name="productionName"
              label="生产单名称"
              rules={[{ required: true, whitespace: true, message: '请输入生产单名称' }]}
            >
              <Input placeholder="请输入生产单名称" maxLength={200} />
            </Form.Item>
            <Form.Item
              name="orderNo"
              label="订单号"
              rules={[{ required: true, whitespace: true, message: '请输入订单号' }]}
            >
              <Input placeholder="请输入订单号" maxLength={120} />
            </Form.Item>
            <Form.Item
              name="productName"
              label="品名"
              rules={[{ required: true, whitespace: true, message: '请输入品名' }]}
            >
              <Input placeholder="请输入品名" maxLength={200} />
            </Form.Item>
            <Form.Item
              name="category"
              label="类别"
              rules={[{ required: true, whitespace: true, message: '请输入类别' }]}
            >
              <Input placeholder="请输入类别" maxLength={100} />
            </Form.Item>
            <Form.Item
              name="manufactureDate"
              label="制造日期"
              rules={[{ required: true, message: '请选择制造日期' }]}
            >
              <Input type="date" />
            </Form.Item>
            <Form.Item className="production-order-relation-field">
              <ProductionRelationFields value={renameRelations} onChange={setRenameRelations} />
            </Form.Item>
          </div>
        </Form>
      </Modal>
    </>
  );
}

function ProductionRelationFields({
  value,
  onChange,
}: {
  value: ProjectRelationTarget[];
  onChange: (value: ProjectRelationTarget[]) => void;
}) {
  const target = value[0] ?? { projectId: '' };
  const [projects, setProjects] = useState<Project[]>([]);
  const [stages, setStages] = useState<ProjectStage[]>([]);
  const [tasks, setTasks] = useState<ProjectTask[]>([]);

  useEffect(() => {
    let active = true;
    void getProjects({ page: 1, size: 100 }).then((result) => {
      if (active) setProjects(result.items);
    });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    setStages([]);
    setTasks([]);
    if (!target.projectId) return;
    void getProjectStages(target.projectId).then(setStages);
  }, [target.projectId]);

  useEffect(() => {
    if (!target.stageId) return;
    void getStageTasks(target.stageId).then(setTasks);
  }, [target.stageId]);

  const update = (patch: Partial<ProjectRelationTarget>) => {
    onChange([{ ...target, ...patch }]);
  };

  return (
    <div className="production-order-relation-fields">
      <div>
        <label>关联项目</label>
        <Select
          allowClear
          showSearch
          optionFilterProp="label"
          placeholder="不关联项目"
          value={target.projectId || undefined}
          popupMatchSelectWidth={false}
          classNames={{ popup: { root: 'production-project-relation-dropdown' } }}
          options={projects.map((project) => ({
            value: project.id,
            label: `${project.projectCode} · ${project.name}`,
          }))}
          onChange={(projectId) => {
            const project = projects.find((item) => item.id === projectId);
            update({
              projectId: projectId || '',
              projectName: project ? `${project.projectCode}·${project.name}` : undefined,
              stageId: undefined,
              stageName: undefined,
              taskId: undefined,
              taskName: undefined,
            });
          }}
        />
      </div>
      <div>
        <label>阶段</label>
        <Select
          allowClear
          placeholder="不关联阶段"
          disabled={!target.projectId}
          value={target.stageId}
          popupMatchSelectWidth={false}
          options={stages.map((stage) => ({ value: stage.id, label: stage.name }))}
          onChange={(stageId) => {
            const stage = stages.find((item) => item.id === stageId);
            update({
              stageId,
              stageName: stage?.name,
              taskId: undefined,
              taskName: undefined,
            });
          }}
        />
      </div>
      <div>
        <label>任务</label>
        <Select
          allowClear
          placeholder="不关联任务"
          disabled={!target.stageId}
          value={target.taskId}
          popupMatchSelectWidth={false}
          options={tasks.map((task) => ({ value: task.id, label: task.name }))}
          onChange={(taskId) => {
            const task = tasks.find((item) => item.id === taskId);
            update({ taskId, taskName: task?.name });
          }}
        />
      </div>
    </div>
  );
}

function ProductionCreateChoice({
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
    <div className="production-order-choice-section">
      <label>
        <i>*</i>
        {label}
      </label>
      <div>
        {options.map((option, index) => (
          <button
            key={option.key}
            type="button"
            className={value === option.key ? 'active' : ''}
            onClick={() => onChange(option.key)}
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
