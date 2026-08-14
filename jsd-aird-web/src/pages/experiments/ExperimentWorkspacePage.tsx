import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CloudUploadOutlined,
  ExclamationCircleOutlined,
  EyeOutlined,
  FileExcelOutlined,
  LoadingOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import {
  App,
  Button,
  Input,
  Result,
  Skeleton,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { buildExperimentSnapshot, parseExperimentSnapshot } from '@/features/experiment-workspace/experiment-workbook';
import type { EditorHandle } from '@/features/template-workspace/types';
import {
  actExperiment,
  createRevision,
  getExperiment,
  listAudits,
  listVersions,
  saveExperiment,
  type ExperimentDetail,
  type ExperimentModel,
} from '@/services/experiments/experiment-api';
import './experiments.css';

const SheetsEditor = lazy(async () => ({
  default: (await import('@/features/template-workspace/UniverSheetsEditor')).UniverSheetsEditor,
}));

type SaveState = 'SAVED' | 'DIRTY' | 'SAVING' | 'FAILED';
type WorkspaceView = 'preview' | 'edit' | 'versions';

const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿',
  PENDING: '待开始',
  IN_PROGRESS: '进行中',
  PENDING_REVIEW: '待审核',
  RETURNED: '已退回',
  COMPLETED: '已完成',
  VOIDED: '已作废',
};

const STATUS_COLOR: Record<string, string> = {
  DRAFT: 'gold',
  PENDING: 'blue',
  IN_PROGRESS: 'processing',
  PENDING_REVIEW: 'purple',
  RETURNED: 'orange',
  COMPLETED: 'green',
  VOIDED: 'default',
};

export function ExperimentWorkspacePage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const { message, modal } = App.useApp();
  const editorRef = useRef<EditorHandle>(null);
  const [detail, setDetail] = useState<ExperimentDetail>();
  const [snapshot, setSnapshot] = useState<Record<string, unknown>>();
  const [editModel, setEditModel] = useState<ExperimentModel>({});
  const [versions, setVersions] = useState<Array<Record<string, unknown>>>([]);
  const [audits, setAudits] = useState<Array<Record<string, unknown>>>([]);
  const [saveState, setSaveState] = useState<SaveState>('SAVED');
  const [view, setView] = useState<WorkspaceView>('preview');
  const [busy, setBusy] = useState(false);
  const [loadError, setLoadError] = useState<string>();
  const savedSnapshotSignatureRef = useRef<string>();
  const isDesktop = useDesktopEditing();

  const load = useCallback(async () => {
    try {
      const d = await getExperiment(id);
      const nextModel = d.editModel ?? {};
      setDetail(d);
      setEditModel(nextModel);
      setSnapshot(buildExperimentSnapshot(nextModel, d.currentVersionId ?? id));
      savedSnapshotSignatureRef.current = JSON.stringify(
        buildExperimentSnapshot(nextModel, d.currentVersionId ?? id),
      );
      const [v, a] = await Promise.all([listVersions(id), listAudits(id)]);
      setVersions(v);
      setAudits(a);
      const editable = !['COMPLETED', 'VOIDED', 'PENDING_REVIEW'].includes(d.summary.status);
      setView(editable ? 'edit' : 'preview');
      setSaveState('SAVED');
    } catch (error) {
      setLoadError(error instanceof Error ? error.message : '实验本加载失败');
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  const markDirty = useCallback(() => {
    setSaveState((current) => (current === 'SAVING' ? current : 'DIRTY'));
  }, []);

  const handleEditorDirty = useCallback(() => {
    const currentSnapshot = editorRef.current?.getSnapshot();
    if (
      currentSnapshot &&
      savedSnapshotSignatureRef.current === JSON.stringify(currentSnapshot)
    ) {
      return;
    }
    markDirty();
  }, [markDirty]);

  const save = async () => {
    if (!detail || !editorRef.current) return false;
    setSaveState('SAVING');
    try {
      const currentSnapshot = editorRef.current.getSnapshot();
      const parsed = parseExperimentSnapshot(currentSnapshot, editModel);
      const v = parsed.title ?? detail.summary.title;
      if (!v.trim()) {
        setSaveState('DIRTY');
        void message.warning('实验标题不能为空');
        return false;
      }
      await saveExperiment(id, {
        revision: detail.summary.revision,
        title: v,
        categoryName: detail.summary.categoryName,
        projectId: detail.summary.projectId,
        stageId: detail.summary.stageId,
        taskId: detail.summary.taskId,
        ownerName: detail.summary.ownerName,
        experimentDate: detail.summary.experimentDate,
        templateVersionId: detail.templateVersionId,
        templateSnapshotHash: detail.templateSnapshotHash,
        templateSnapshot: detail.templateSnapshot,
        editModel: parsed,
      });
      savedSnapshotSignatureRef.current = JSON.stringify(currentSnapshot);
      setSnapshot(currentSnapshot);
      setEditModel(parsed);
      setSaveState('SAVED');
      void message.success('草稿已保存');
      await load();
      return true;
    } catch (error) {
      setSaveState('FAILED');
      void message.error(error instanceof Error ? error.message : '保存失败，本地内容仍然保留');
      return false;
    }
  };

  const act = async (
    action: 'start' | 'submit-review' | 'approve' | 'return' | 'void',
    needsComment = false,
  ) => {
    if (!detail) return;
    let comment: string | undefined;
    if (needsComment) {
      comment = await new Promise<string | undefined>((resolve) => {
        let text = '';
        modal.confirm({
          title: action === 'return' ? '退回原因' : '作废原因',
          content: <Input.TextArea onChange={(e) => { text = e.target.value; }} />,
          okText: '确定',
          onOk: () => resolve(text),
          onCancel: () => resolve(undefined),
        });
      });
      if (!comment) return;
    }
    setBusy(true);
    try {
      await actExperiment(id, action, detail.summary.revision, comment);
      void message.success('状态已更新');
      await load();
    } finally {
      setBusy(false);
    }
  };

  const createRev = async () => {
    if (!detail) return;
    await createRevision(id, detail.summary.revision, '实验结果修订');
    void message.success('修订版本已创建');
    await load();
  };

  if (loadError) {
    return (
      <Result
        status="error"
        title="实验本加载失败"
        subTitle={loadError}
        extra={<Button onClick={() => navigate('/experiments/list')}>返回实验中心</Button>}
      />
    );
  }
  if (!detail || !snapshot) return <Skeleton active paragraph={{ rows: 12 }} />;

  const status = detail.summary.status;
  const readonly = ['COMPLETED', 'VOIDED', 'PENDING_REVIEW'].includes(status);
  const editable = !readonly && isDesktop && view === 'edit';

  return (
    <section className="workspace-shell template-business-workspace" aria-label={`${detail.summary.title}实验工作台`}>
      <header className="workspace-header">
        <div className="workspace-identity">
          <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/experiments/list')}>
            返回
          </Button>
          <span className="workspace-title-block">
            <Typography.Text type="secondary" className="workspace-breadcrumb">
              实验中心 / 实验记录
            </Typography.Text>
            <Typography.Text strong>{detail.summary.title}</Typography.Text>
          </span>
          <span className="workspace-meta-item">V{detail.summary.versionNo}.0</span>
          <span className="workspace-meta-item workspace-format">
            <FileExcelOutlined /> 实验记录
          </span>
          <Tag color={STATUS_COLOR[status] ?? 'blue'}>{STATUS_TEXT[status] ?? status}</Tag>
        </div>
        <Space wrap>
          <SaveStateIndicator state={saveState} />
          {view === 'edit' && (
            <Button
              type={saveState === 'DIRTY' || saveState === 'SAVING' ? 'primary' : 'default'}
              className="workspace-save-button"
              icon={<SaveOutlined />}
              disabled={!editable || saveState === 'SAVED'}
              loading={saveState === 'SAVING'}
              onClick={() => void save()}
            >
              保存草稿
            </Button>
          )}
          {(status === 'DRAFT' || status === 'PENDING') && (
            <Button type="primary" loading={busy} onClick={() => void act('start')}>
              开始实验
            </Button>
          )}
          {(status === 'IN_PROGRESS' || status === 'RETURNED') && (
            <Button type="primary" loading={busy} onClick={() => void act('submit-review')}>
              提交审核
            </Button>
          )}
          {status === 'PENDING_REVIEW' && (
            <>
              <Button onClick={() => void act('return', true)}>退回</Button>
              <Button type="primary" onClick={() => void act('approve')}>审核通过</Button>
            </>
          )}
          {status === 'COMPLETED' && (
            <Button
              type="primary"
              onClick={() => {
                modal.confirm({
                  title: '创建修订版本',
                  content: '将基于当前正式版本复制数据并创建草稿。',
                  okText: '创建修订',
                  onOk: () => void createRev(),
                });
              }}
            >
              创建修订版本
            </Button>
          )}
          {!['COMPLETED', 'VOIDED'].includes(status) && (
            <Button danger loading={busy} onClick={() => void act('void', true)}>
              作废
            </Button>
          )}
        </Space>
      </header>

      <nav className="workspace-view-tabs" aria-label="实验页面">
        <Tabs
          activeKey={view}
          onChange={(key) => setView(key as WorkspaceView)}
          items={[
            { key: 'preview', label: <><EyeOutlined /> 预览</> },
            { key: 'edit', label: '编辑', disabled: readonly },
            { key: 'versions', label: '版本记录' },
          ]}
        />
      </nav>

      {view === 'versions' ? (
        <VersionView
          detail={detail}
          versions={versions}
          audits={audits}
          onChangeVersion={() => undefined}
        />
      ) : (
        <div className="workspace-main-stage">
          <div className="template-workspace-grid prototype-workspace-grid">
            <main className={`workspace-canvas ${view === 'preview' ? 'is-preview' : ''}`}>
              <Suspense fallback={<Spin indicator={<LoadingOutlined spin />} tip="正在加载实验记录" fullscreen />}>
                <div className={view === 'preview' ? 'document-readonly' : undefined}>
                  <SheetsEditor
                    ref={editorRef}
                    snapshot={snapshot}
                    bindings={[]}
                    editable={editable}
                    onDirty={handleEditorDirty}
                    onEditorValue={() => undefined}
                  />
                </div>
              </Suspense>
            </main>
          </div>
        </div>
      )}
    </section>
  );
}

function VersionView({
  detail,
  versions,
  audits,
}: {
  detail: ExperimentDetail;
  versions: Array<Record<string, unknown>>;
  audits: Array<Record<string, unknown>>;
  onChangeVersion: () => void;
}) {
  return (
    <main className="version-history-page">
      <div className="version-history-heading">
        <Typography.Title level={4}>版本记录</Typography.Title>
        <Typography.Text type="secondary">
          实验本按版本管理，正式版本发布后锁定，修订会创建新的草稿版本。
        </Typography.Text>
      </div>
      <div className="version-history-list">
        {(versions.length ? versions : [{
          versionNo: detail.summary.versionNo,
          status: detail.summary.status,
          revisionReason: '',
          publishedAt: detail.summary.updatedAt,
          createdAt: detail.summary.updatedAt,
        }]).map((item) => (
          <article className="version-history-item" key={String(item.id ?? item.versionNo)}>
            <div className="version-marker">V{String(item.versionNo)}.0</div>
            <div>
              <Space wrap>
                <Typography.Text strong>
                  {Number(item.versionNo) === detail.summary.versionNo ? '当前版本' : `版本 V${String(item.versionNo)}.0`}
                </Typography.Text>
                <Tag color={STATUS_COLOR[String(item.status)] ?? 'blue'}>
                  {STATUS_TEXT[String(item.status)] ?? String(item.status)}
                </Tag>
              </Space>
              <Typography.Paragraph type="secondary">
                最近更新：{new Date(String(item.updatedAt ?? item.publishedAt ?? item.createdAt)).toLocaleString('zh-CN')}
                {typeof item.revisionReason === 'string' && item.revisionReason ? ` · 修订原因：${item.revisionReason}` : ''}
              </Typography.Paragraph>
            </div>
          </article>
        ))}
      </div>
      <div className="version-history-heading" style={{ marginTop: 24 }}>
        <Typography.Title level={5}>操作日志</Typography.Title>
      </div>
      <Table
        rowKey="id"
        pagination={false}
        dataSource={audits}
        columns={[
          { title: '操作', dataIndex: 'action' },
          { title: '操作人', dataIndex: 'operatorName' },
          { title: '时间', dataIndex: 'createdAt' },
        ]}
      />
    </main>
  );
}

function SaveStateIndicator({ state }: { state: SaveState }) {
  const config = {
    SAVED: { icon: <CheckCircleOutlined />, text: '已保存', color: 'var(--app-success)' },
    DIRTY: { icon: <CloudUploadOutlined />, text: '未保存', color: 'var(--app-warning)' },
    SAVING: { icon: <LoadingOutlined spin />, text: '保存中', color: 'var(--app-primary)' },
    FAILED: { icon: <ExclamationCircleOutlined />, text: '保存失败', color: 'var(--app-danger)' },
  }[state];
  return (
    <span className="save-state" style={{ color: config.color }} aria-live="polite">
      {config.icon}
      {config.text}
    </span>
  );
}

function useDesktopEditing() {
  const [matches, setMatches] = useState(() => window.matchMedia('(min-width: 1100px)').matches);
  useEffect(() => {
    const media = window.matchMedia('(min-width: 1100px)');
    const update = () => setMatches(media.matches);
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);
  return matches;
}
