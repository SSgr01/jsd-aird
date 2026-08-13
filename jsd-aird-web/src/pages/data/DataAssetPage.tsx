import {
  ArrowLeftOutlined,
  DownloadOutlined,
  EyeOutlined,
  HistoryOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { Alert, App, Button, Select, Space, Spin, Tabs, Tag, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { FilePreviewModal, downloadPreviewFile, type FilePreviewDescriptor } from '@/components/file-preview';
import {
  DataFieldDataBrowser,
  DataFieldStructureBrowser,
  DataWorkbenchShell,
  DataWorkbookCanvas,
  WorkbenchPanelHeader,
} from '@/features/data-workbench/DataWorkbench';
import type { EditorHandle, EditorSelection } from '@/features/template-workspace/types';
import {
  dataApi,
  dataTypeOptions,
  type DataAssetDetail,
  type DataFieldValueView,
  type DataWorkbookFieldDefinition,
  type DataRevision,
  type DataSourceAnchor,
  type DataWorkbookSnapshot,
} from '@/services/data/data-api';

type PanelTab = 'data' | 'structure' | 'revisions';

const assetStatusLabels: Record<string, string> = {
  ACTIVE: '当前有效',
  RETIRED: '已停用',
  DRAFT: '草稿',
};

export function DataAssetPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const { message } = App.useApp();
  const editorRef = useRef<EditorHandle>(null);
  const [asset, setAsset] = useState<DataAssetDetail>();
  const [revisions, setRevisions] = useState<DataRevision[]>([]);
  const [sources, setSources] = useState<DataSourceAnchor[]>([]);
  const [selectedRevisionId, setSelectedRevisionId] = useState<string>();
  const [workbook, setWorkbook] = useState<DataWorkbookSnapshot>();
  const [selectedFieldKey, setSelectedFieldKey] = useState<string>();
  const [selectedBindingId, setSelectedBindingId] = useState<string>();
  const [selectedCell, setSelectedCell] = useState<EditorSelection>();
  const [activeTab, setActiveTab] = useState<PanelTab>('data');
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();
  const [loading, setLoading] = useState(true);
  const [workbookLoading, setWorkbookLoading] = useState(false);

  const loadOverview = useCallback(async () => {
    setLoading(true);
    try {
      const [detail, revisionList, sourceList] = await Promise.all([
        dataApi.getAsset(id),
        dataApi.listRevisions(id),
        dataApi.listSources(id),
      ]);
      setAsset(detail);
      setRevisions(revisionList);
      setSources(sourceList);
      setSelectedRevisionId((current) => {
        if (current && revisionList.some((item) => item.id === current)) return current;
        return detail.currentRevisionId || revisionList[0]?.id;
      });
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '数据资产加载失败');
    } finally {
      setLoading(false);
    }
  }, [id, message]);

  useEffect(() => { void loadOverview(); }, [loadOverview]);

  useEffect(() => {
    if (!selectedRevisionId) return;
    let cancelled = false;
    setWorkbookLoading(true);
    void dataApi.getAssetWorkbookSnapshot(id, selectedRevisionId).then((nextWorkbook) => {
      if (cancelled) return;
      setWorkbook(nextWorkbook);
      setSelectedFieldKey(fieldKey(nextWorkbook.fields[0]));
      const firstField = nextWorkbook.fields[0];
      const firstDefinition = firstField ? definitionForValue(nextWorkbook, firstField)
        : nextWorkbook.fieldDefinitions?.[0];
      setSelectedBindingId(firstDefinition?.bindingId || firstField?.bindingId);
      setSelectedCell(undefined);
    }).catch((error) => {
      if (!cancelled) void message.error(error instanceof Error ? error.message : '修订工作簿加载失败');
    }).finally(() => {
      if (!cancelled) setWorkbookLoading(false);
    });
    return () => { cancelled = true; };
  }, [id, message, revisions, selectedRevisionId]);

  const selectedRevision = revisions.find((item) => item.id === selectedRevisionId);
  const selectedSources = useMemo(
    () => sources.filter((item) => item.revisionId === selectedRevisionId),
    [selectedRevisionId, sources],
  );
  const fields = workbook?.fields || [];

  if (loading || !asset) return <div className="data-workbench-loading"><Spin /></div>;

  const sourceFile = (): FilePreviewDescriptor | undefined => {
    const anchor = selectedSources[0];
    const fileId = selectedRevision?.sourceFileId || anchor?.fileId;
    if (!fileId) return undefined;
    return {
      fileName: selectedRevision?.sourceFileName || workbook?.fileName || `${asset.displayName || asset.assetKey}-原始文件`,
      load: () => dataApi.sourceBlob(fileId),
    };
  };

  const previewSource = () => {
    const file = sourceFile();
    if (!file) {
      void message.warning('当前修订没有可用的原始文件');
      return;
    }
    setPreviewFile(file);
  };

  const downloadSource = async () => {
    const file = sourceFile();
    if (!file) {
      void message.warning('当前修订没有可用的原始文件');
      return;
    }
    try {
      await downloadPreviewFile(file);
      void message.success('原文件下载已开始');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '原始文件下载失败');
    }
  };

  const selectField = (field: DataFieldValueView) => {
    setSelectedFieldKey(fieldKey(field));
    setSelectedBindingId(definitionForValue(workbook, field)?.bindingId || field.bindingId);
    if (field.sheetId && field.address) editorRef.current?.focusCell?.(field.sheetId, field.address);
  };

  const selectDefinition = (field: DataWorkbookFieldDefinition) => {
    setSelectedBindingId(field.bindingId);
    const value = fields.find((item) => item.componentId === field.componentId
      && (item.bindingId === field.bindingId || item.fieldCode === field.fieldCode));
    if (value) setSelectedFieldKey(fieldKey(value));
    if (value?.sheetId && value.address) editorRef.current?.focusCell?.(value.sheetId, value.address);
    else if (field.sheetId && field.sourceRange) editorRef.current?.focusRange?.(field.sheetId, field.sourceRange);
  };

  const handleSelection = (selection: EditorSelection) => {
    setSelectedCell(selection);
    const exact = fields.find((field) => field.sheetId === selection.sheetId
      && field.address?.toUpperCase() === selection.address.toUpperCase());
    if (exact) {
      setSelectedFieldKey(fieldKey(exact));
      setSelectedBindingId(definitionForValue(workbook, exact)?.bindingId || exact.bindingId);
    }
  };

  const currentRevision = selectedRevisionId === asset.currentRevisionId;
  const meta = (
    <Space wrap size={8}>
      <Tag color="blue">{dataTypeOptions.find((item) => item.value === asset.targetDataType)?.label || '研发数据'}</Tag>
      <Tag color={currentRevision ? 'success' : 'default'}>{currentRevision ? '当前修订' : '历史修订'}</Tag>
      <Tag>{assetStatusLabels[asset.status] || '已入库'}</Tag>
      {selectedRevision ? <Typography.Text type="secondary">修订 V{selectedRevision.revisionNo}</Typography.Text> : null}
    </Space>
  );

  return (
    <DataWorkbenchShell
      breadcrumb="数据中心 / 数据资产"
      title={asset.displayName || '未命名数据资产'}
      meta={meta}
      actions={<>
        <Button icon={<ReloadOutlined />} onClick={() => void loadOverview()}>刷新</Button>
        <Button icon={<EyeOutlined />} disabled={!sourceFile()} onClick={previewSource}>预览原文件</Button>
        <Button icon={<DownloadOutlined />} disabled={!sourceFile()} onClick={() => void downloadSource()}>下载原文件</Button>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/data/view')}>返回数据查看</Button>
      </>}
      notice={!sourceFile() ? <Alert type="info" showIcon message="当前修订没有可定位的原始文件，右侧正式数据仍可正常查看。" /> : undefined}
      canvas={<DataWorkbookCanvas
        ref={editorRef}
        workbook={workbook}
        loading={workbookLoading}
        editable={false}
        onSelectionChange={handleSelection}
      />}
      panel={<>
        <Tabs
          className="data-workbench-tabs"
          activeKey={activeTab}
          onChange={(value) => setActiveTab(value as PanelTab)}
          items={[
            { key: 'data', label: '字段数据' },
            { key: 'structure', label: '字段结构' },
            { key: 'revisions', label: '修订历史' },
          ]}
        />
        {activeTab === 'data' ? (
          <DataFieldDataBrowser
            workbook={workbook}
            selectedFieldKey={selectedFieldKey}
            selectedCell={selectedCell}
            onSelectField={selectField}
            renderFieldMeta={(field) => <Space wrap size={4}>
              <Tag>{dataTypeLabel(field.valueType)}</Tag>
              <Tag>{sourceLabel(field.valueSource)}</Tag>
            </Space>}
            emptyDescription="当前修订没有可展示的结构化字段"
          />
        ) : null}
        {activeTab === 'structure' ? (
          <DataFieldStructureBrowser
            workbook={workbook}
            selectedBindingId={selectedBindingId}
            onSelectField={selectDefinition}
          />
        ) : null}
        {activeTab === 'revisions' ? (
          <RevisionPanel
            revisions={revisions}
            selectedRevision={selectedRevision}
            selectedRevisionId={selectedRevisionId}
            onRevisionChange={setSelectedRevisionId}
            onOpenImport={(importJobId) => navigate(`/data/import-jobs/${importJobId}`)}
          />
        ) : null}
      </>}
      footer={selectedRevision ? (
        <Space wrap>
          <HistoryOutlined />
          <Typography.Text strong>正在查看修订 V{selectedRevision.revisionNo}</Typography.Text>
          <Typography.Text type="secondary">{new Date(selectedRevision.createdAt).toLocaleString('zh-CN')}</Typography.Text>
          {!currentRevision ? <Button size="small" onClick={() => setSelectedRevisionId(asset.currentRevisionId)}>返回当前修订</Button> : null}
        </Space>
      ) : undefined}
    >
      <FilePreviewModal open={Boolean(previewFile)} file={previewFile} onClose={() => setPreviewFile(undefined)} />
    </DataWorkbenchShell>
  );
}

function RevisionPanel({ revisions, selectedRevision, selectedRevisionId, onRevisionChange, onOpenImport }: {
  revisions: DataRevision[];
  selectedRevision?: DataRevision;
  selectedRevisionId?: string;
  onRevisionChange: (revisionId: string) => void;
  onOpenImport: (importJobId: string) => void;
}) {
  return <div className="data-panel-body">
    <WorkbenchPanelHeader title="修订历史" description="切换修订后，左侧同步显示当时的来源文件和生效数据" />
    <Select
      value={selectedRevisionId}
      onChange={onRevisionChange}
      options={revisions.map((item) => ({
        value: item.id,
        label: `修订 V${item.revisionNo} · ${new Date(item.createdAt).toLocaleDateString('zh-CN')}`,
      }))}
      style={{ width: '100%' }}
      placeholder="选择修订"
    />
    {selectedRevision ? <section className="data-panel-section data-revision-summary">
      <dl>
        <div><dt>来源文件</dt><dd>{selectedRevision.sourceFileName || '暂无文件名'}</dd></div>
        <div><dt>包含工作表</dt><dd>{selectedRevision.sheetNames?.join('、') || '暂无'}</dd></div>
        <div><dt>导入记录</dt><dd>{selectedRevision.recordCount ?? 0} 条</dd></div>
        <div><dt>创建时间</dt><dd>{new Date(selectedRevision.createdAt).toLocaleString('zh-CN')}</dd></div>
      </dl>
      <Button block onClick={() => onOpenImport(selectedRevision.importJobId)}>查看对应导入任务</Button>
    </section> : null}
  </div>;
}

function fieldKey(field?: DataFieldValueView) {
  if (!field) return undefined;
  return [field.recordId, field.bindingId, field.valuePath, field.sheetId, field.address].join('|');
}

function definitionForValue(workbook: DataWorkbookSnapshot | undefined, field: DataFieldValueView) {
  return workbook?.fieldDefinitions?.find((item) => item.componentId === field.componentId
    && (item.bindingId === field.bindingId || item.fieldCode === field.fieldCode));
}

function sourceLabel(source?: string) {
  const labels: Record<string, string> = {
    INPUT: '人工录入', USER_INPUT: '人工录入', FORMULA: '公式结果', DERIVED: '派生结果',
    STATIC: '静态说明', REFERENCE: '引用值', MIXED: '混合来源', UNKNOWN: '来源待确认',
  };
  return labels[(source || 'UNKNOWN').toUpperCase()] || '数据值';
}

function dataTypeLabel(value?: string) {
  const labels: Record<string, string> = {
    TEXT: '文本', STRING: '文本', NUMBER: '数值', DECIMAL: '小数', INTEGER: '整数',
    DATE: '日期', DATETIME: '日期时间', BOOLEAN: '是/否', ENUM: '选项',
  };
  return labels[(value || 'TEXT').toUpperCase()] || '文本';
}
