import {
  ArrowLeftOutlined,
  DownloadOutlined,
  EyeOutlined,
  LoadingOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { formatTime } from '@/utils/date';
import { SaveStateBadge, type SaveState } from '@/components/SaveStateBadge';
import { VersionHistoryPanel } from '@/components/version-history/VersionHistoryPanel';
import {
  App,
  Button,
  Result,
  Skeleton,
  Space,
  Spin,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';

import { buildExperimentSnapshot, parseExperimentSnapshot } from '@/features/experiment-workspace/experiment-workbook';
import type { EditorHandle } from '@/features/template-workspace/types';
import {
  createRevision,
  getExperiment,
  listVersions,
  publishExperiment,
  saveExperiment,
  type ExperimentDetail,
  type ExperimentModel,
  type ExperimentVersion,
  exportExperiment,
} from '@/services/experiments/experiment-api';
import { downloadBlob } from '@/services/files/file-api';
import './experiments.css';

const SheetsEditor = lazy(async () => ({
  default: (await import('@/features/template-workspace/UniverSheetsEditor')).UniverSheetsEditor,
}));

const DocsEditor = lazy(async () => ({
  default: (await import('@/features/template-workspace/UniverDocsEditor')).UniverDocsEditor,
}));

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

export function ExperimentWorkspacePage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const returnTo = typeof location.state?.returnTo === 'string' ? location.state.returnTo : '/experiments/list';
  const goBack = () => navigate(returnTo);
  const { message, modal } = App.useApp();
  const editorRef = useRef<EditorHandle>(null);
  const [detail, setDetail] = useState<ExperimentDetail>();
  const [snapshot, setSnapshot] = useState<Record<string, unknown>>();
  const [editModel, setEditModel] = useState<ExperimentModel>({});
  const [versions, setVersions] = useState<ExperimentVersion[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<ExperimentVersion>();
  const [view, setView] = useState<WorkspaceView>('preview');
  const [busy, setBusy] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [saveState, setSaveState] = useState<SaveState>('SAVED');
  const [loadError, setLoadError] = useState<string>();
  const isDesktop = useDesktopEditing();

  const load = useCallback(async () => {
    setSaveState('SAVED');
    try {
      const d = await getExperiment(id);
      const nextModel: ExperimentModel = { ...(d.editModel ?? {}) };
      if (nextModel.documentFormat === 'word' && d.templateSnapshot && !nextModel.documentSnapshot) {
        nextModel.documentSnapshot = d.templateSnapshot;
      }
      setDetail(d);
      setEditModel(nextModel);
      const initialSnapshot = buildExperimentSnapshot(nextModel, d.currentVersionId ?? id, d.templateSnapshot);
      setSnapshot(initialSnapshot);
      const v = await listVersions(id);
      setVersions(v);
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

  const save = async () => {
    if (!detail) return false;
    setSaveState('SAVING');
    try {
      // Keep saving functional while the lazy Univer editor is starting by
      // using the last loaded snapshot as the document baseline; once the
      // editor is ready its live snapshot remains authoritative.
      const currentSnapshot = editorRef.current?.getSnapshot()
        ?? snapshot
        ?? buildExperimentSnapshot(editModel, detail.currentVersionId ?? id, detail.templateSnapshot);
      const nextModel = parseExperimentSnapshot(currentSnapshot, editModel);
      const v = nextModel.title ?? detail.summary.title;
      if (!v.trim()) {
        void message.warning('实验标题不能为空');
        setSaveState('DIRTY');
        return false;
      }
      const savedDetail = await saveExperiment(id, {
        revision: detail.summary.revision,
        experimentNo: detail.summary.experimentNo,
        title: v,
        categoryId: detail.summary.categoryId,
        categoryName: detail.summary.categoryName,
        projectId: detail.summary.projectId,
        stageId: detail.summary.stageId,
        taskId: detail.summary.taskId,
        ownerName: detail.summary.ownerName,
        experimentDate: detail.summary.experimentDate,
        templateVersionId: detail.templateVersionId,
        templateSnapshotHash: detail.templateSnapshotHash,
        templateSnapshot: detail.templateSnapshot,
        editModel: nextModel,
      });
      // Keep the live editor mounted after a draft save. Updating `snapshot`
      // here would trigger UniverSheetsEditor’s snapshot effect, destroy the
      // current workspace, and recreate it from a possibly stale response.
      setDetail(savedDetail);
      setEditModel(nextModel);
      setSaveState('SAVED');
      void message.success('草稿已保存');
      return true;
    } catch (error) {
      setSaveState('DIRTY');
      void message.error(error instanceof Error ? error.message : '保存失败，本地内容仍然保留');
      return false;
    }
  };

  const publish = async () => {
    if (!detail) return;
    if (saveState !== 'SAVED') {
      void message.warning('当前内容尚未保存，请先保存后再发布');
      return;
    }
    setBusy(true);
    try {
      await publishExperiment(id, detail.summary.revision);
      void message.success('实验已发布，发布后不可编辑');
      await load();
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '实验发布失败');
    } finally {
      setBusy(false);
    }
  };

  const reviseCompletedExperiment = () => {
    if (!detail || detail.summary.status !== 'COMPLETED') return;
    modal.confirm({
      title: '创建修订版本？',
      content: '当前已完成版本会永久保留；系统将复制其内容，创建一个新的草稿版本供修改。',
      okText: '创建修订',
      cancelText: '取消',
      onOk: async () => {
        setBusy(true);
        try {
          await createRevision(id, detail.summary.revision, '修改已完成实验');
          void message.success('已创建修订草稿，原完成版本未改变');
          await load();
        } finally {
          setBusy(false);
        }
      },
    });
  };

  const exportRecord = async () => {
    if (!detail) return;
    setExporting(true);
    try {
      const blob = await exportExperiment(id);
      const extension = detail.editModel.documentFormat === 'word' ? 'docx' : 'xlsx';
      const versionSuffix = versions.length > 0 ? `-V${versions[0]?.versionNo ?? detail.summary.versionNo}` : '';
      downloadBlob(blob, `${detail.summary.title || detail.summary.experimentNo}${versionSuffix}.${extension}`);
      void message.success('实验已导出');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '实验导出失败');
    } finally {
      setExporting(false);
    }
  };

  const changeView = (key: WorkspaceView) => {
    if (key === 'versions' && versions.length === 0) {
      void message.info('发布后才会生成版本记录');
      return;
    }
    if (saveState !== 'SAVED' && key === 'versions') {
      void message.warning('当前内容尚未保存，请先保存后再查看版本记录');
      return;
    }
    setSelectedVersion(undefined);
    setView(key);
  };

  if (loadError) {
    return (
      <Result
        status="error"
        title="实验本加载失败"
        subTitle={loadError}
        extra={<Button onClick={goBack}>返回</Button>}
      />
    );
  }
  if (!detail || !snapshot) return <Skeleton active paragraph={{ rows: 12 }} />;

  const status = detail.summary.status;
  const readonly = ['COMPLETED', 'VOIDED', 'PENDING_REVIEW'].includes(status);
  const editable = !readonly && isDesktop && view === 'edit';
  const canPublish = !['COMPLETED', 'VOIDED'].includes(status)
    && saveState === 'SAVED'
    && view !== 'versions'
    && !selectedVersion;

  return (
    <section className="workspace-shell template-business-workspace experiment-workspace-shell" aria-label={`${detail.summary.title}实验工作台`}>
      <header className="workspace-header">
        <div className="workspace-identity">
          <Button type="text" icon={<ArrowLeftOutlined />} onClick={goBack}>
            返回
          </Button>
          <span className="workspace-title-block">
            <Typography.Text type="secondary" className="workspace-breadcrumb">
              实验中心 / 实验记录
            </Typography.Text>
            <Typography.Text strong>{detail.summary.title}</Typography.Text>
          </span>
          <Tag color={versions.length > 0 ? 'blue' : 'default'}>
            {versions.length > 0 ? `V${versions[0]?.versionNo ?? detail.summary.versionNo}` : '未发布'}
          </Tag>
        </div>
        <Space wrap>
          <SaveStateBadge state={saveState} />
          <Button className="workspace-export-button" icon={<DownloadOutlined />} loading={exporting} onClick={() => void exportRecord()}>导出</Button>
          {status === 'COMPLETED' && (
            <Button loading={busy} onClick={reviseCompletedExperiment}>创建修订</Button>
          )}
          <Button
            type="primary"
            icon={<SaveOutlined />}
            disabled={!editable}
            loading={saveState === 'SAVING'}
            onClick={() => void save()}
          >
            保存草稿
          </Button>
          <Button
            type="primary"
            className="workspace-publish-button"
            loading={busy}
            disabled={!canPublish}
            onClick={() => void publish()}
          >
            发布
          </Button>
        </Space>
      </header>

      <nav className="workspace-view-tabs" aria-label="实验页面">
        <Tabs
          activeKey={view}
          onChange={(key) => changeView(key as WorkspaceView)}
          items={[
            { key: 'preview', label: <><EyeOutlined /> 预览</> },
            { key: 'edit', label: '编辑', disabled: readonly },
            ...(versions.length > 0 ? [{ key: 'versions', label: '版本记录' }] : []),
          ]}
        />
      </nav>

      {view === 'versions' ? (
        selectedVersion ? <ExperimentHistoricalVersion version={selectedVersion} onBack={() => setSelectedVersion(undefined)} /> : <VersionView versions={versions} onChangeVersion={setSelectedVersion} />
      ) : (
        <div className="workspace-main-stage">
          <div className="template-workspace-grid prototype-workspace-grid">
            <main className={`workspace-canvas ${view === 'preview' ? 'is-preview' : ''}`}>
              <Suspense fallback={<Spin indicator={<LoadingOutlined spin />} tip="正在加载实验记录" fullscreen />}>
                {editModel.documentFormat === 'word' ? (
                  <div className={view === 'preview' ? 'document-readonly' : undefined}>
                    <DocsEditor
                      ref={editorRef}
                      snapshot={snapshot}
                      editable={editable}
                      onDirty={() => setSaveState('DIRTY')}
                    />
                  </div>
                ) : (
                  <div className={view === 'preview' ? 'document-readonly' : undefined}>
                    <SheetsEditor
                      ref={editorRef}
                      snapshot={snapshot}
                      bindings={[]}
                      editable={editable}
                      onEditorValue={() => undefined}
                      onDirty={() => setSaveState('DIRTY')}
                    />
                  </div>
                )}
              </Suspense>
            </main>
          </div>
        </div>
      )}
    </section>
  );
}

function VersionView({
  versions,
  onChangeVersion,
}: {
  versions: ExperimentVersion[];
  onChangeVersion: (version: ExperimentVersion) => void;
}) {
  return (
    <main className="workspace-canvas project-versions-panel">
      <div className="project-versions-content">
        <VersionHistoryPanel
          versions={versions}
          onSelect={onChangeVersion}
          description="每次发布都会保留完整数据快照，可追溯发布人和发布时间。"
          getLabel={(item) => STATUS_TEXT[item.status] ?? item.status}
        />
      </div>
    </main>
  );
}

function ExperimentHistoricalVersion({ version, onBack }: { version: ExperimentVersion; onBack: () => void }) {
  const historicalSnapshot = buildExperimentSnapshot(version.editModel ?? {}, version.id, version.templateSnapshot);
  const word = version.editModel?.documentFormat === 'word' || 'body' in historicalSnapshot;
  return <div className="workspace-main-stage"><div className="project-version-preview">
    <header className="project-versions-header"><Button onClick={onBack}>返回版本列表</Button><Typography.Title level={4}>版本 V{version.versionNo}</Typography.Title><Typography.Text type="secondary">{formatTime(version.createdAt)} · {version.createdBy ?? '未知'}</Typography.Text></header>
    <main className="workspace-canvas document-readonly"><Suspense fallback={<Spin indicator={<LoadingOutlined spin />} />}>{word
      ? <DocsEditor snapshot={historicalSnapshot} editable={false} onDirty={() => undefined} />
      : <SheetsEditor snapshot={historicalSnapshot} bindings={[]} editable={false} onEditorValue={() => undefined} onDirty={() => undefined} />}</Suspense></main>
  </div></div>;
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
