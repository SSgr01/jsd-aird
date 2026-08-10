import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CloudUploadOutlined,
  DownloadOutlined,
  DownOutlined,
  ExclamationCircleOutlined,
  EyeOutlined,
  FileExcelOutlined,
  FileWordOutlined,
  LoadingOutlined,
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
  SaveOutlined,
  SendOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Dropdown,
  Input,
  Modal,
  Progress,
  Result,
  Skeleton,
  Space,
  Spin,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import {
  candidateBinding,
  bindingMatchesIdentity,
  fieldMatchesIdentity,
  prepareFormalMappings,
  prepareFormalSchema,
  readFieldModel,
  removeBusinessField,
  templateLocalFieldCode,
  updateBusinessField,
  writeFieldModel,
} from '@/features/template-workspace/field-model';
import {
  synchronizeStructuredData,
  type BindingValuePair,
} from '@/features/template-workspace/structured-data';
import { createCustomFieldWorkspace } from '@/features/template-workspace/custom-field-operations';
import {
  groupCode,
  normalizeFieldModel,
  normalizeGroupName,
} from '@/features/template-workspace/group-normalizer';
import {
  acceptRecognitionReviewItem,
  mergeRecognitionReview,
} from '@/features/template-workspace/recognition-review';
import { getAtPath, setAtPath } from '@/features/template-workspace/path-utils';
import {
  resolveBindingSelection,
  selectionCycleKey,
} from '@/features/template-workspace/selection-resolver';
import {
  publishReadinessBlocker,
  type PublishReviewSyncState,
} from '@/features/template-workspace/publish-readiness';
import { migrateWorkspaceStructure } from '@/features/template-workspace/structure-migration';
import {
  isSingleCellAddress,
  potentialFieldLabel,
} from '@/features/template-workspace/live-field-discovery';
import type {
  BusinessField,
  EditorHandle,
  EditorSelection,
  FieldModel,
  MatrixModel,
  TemplateBinding,
  TemplateVersionHistoryItem,
  TemplateWorkspace,
  WorkbookStructureOperation,
} from '@/features/template-workspace/types';
import { HttpError } from '@/services/http/errors';
import { templateApi } from '@/services/templates/template-api';
import type {
  RecognitionAction,
  RecognitionReview,
  RecognitionReviewItem,
  QualityAction,
  TemplateQualityIssue,
  TemplateImportJob,
} from '@/services/templates/template-api';
import { DocumentOutlinePanel } from '@/features/template-workspace/DocumentOutlinePanel';

function isRegionModelField(field: BusinessField) {
  return field.displayRole === 'REGION' || ['FORM_REGION', 'ROW_TABLE', 'COLUMN_TABLE', 'MATRIX', 'TABLE_REGION'].includes(
    field.kind,
  ) || field.mappingKind === 'REPEAT_REGION';
}
import type { WordPatchOperation } from '@/features/template-workspace/WordTemplateEditor';

import {
  type CoordinateTarget,
  type FieldManagerTab,
  TemplateFieldManager,
} from './TemplateFieldManager';
import { normalizeAddress, validateAddress } from './coordinates';

const SheetsEditor = lazy(async () => {
  const module = await import('@/features/template-workspace/UniverSheetsEditor');
  return { default: module.UniverSheetsEditor };
});

const DocsEditor = lazy(async () => {
  const module = await import('@/features/template-workspace/UniverDocsEditor');
  return { default: module.UniverDocsEditor };
});

type SaveState = 'SAVED' | 'DIRTY' | 'SAVING' | 'FAILED' | 'CONFLICT';
type WorkspaceView = 'preview' | 'edit' | 'versions';
type ReviewSyncState = PublishReviewSyncState;

export function TemplateWorkspacePage() {
  const { versionId } = useParams<{ versionId: string }>();
  const navigate = useNavigate();
  const { message, modal } = App.useApp();
  const editorRef = useRef<EditorHandle>(null);
  const autoAppendKeysRef = useRef(new Set<string>());
  const selectionCycleRef = useRef({ key: '', index: 0 });
  const unboundChangeTimerRef = useRef<number>();
  const [workspace, setWorkspace] = useState<TemplateWorkspace>();
  const [snapshot, setSnapshot] = useState<Record<string, unknown>>();
  const [schema, setSchema] = useState<Record<string, unknown>>({});
  const [mapping, setMapping] = useState<TemplateBinding[]>([]);
  const [fieldModel, setFieldModel] = useState<FieldModel>({
    modelVersion: 4,
    groups: [],
    fields: [],
    blocks: [],
    semanticAnnotations: [],
  });
  const [data, setData] = useState<Record<string, unknown>>({});
  const [view, setView] = useState<WorkspaceView>('edit');
  const [selectedFieldId, setSelectedFieldId] = useState<string>();
  const [selectedRecognitionItemId, setSelectedRecognitionItemId] = useState<string>();
  const [saveState, setSaveState] = useState<SaveState>('SAVED');
  const [reviewSyncState, setReviewSyncState] = useState<ReviewSyncState>('FRESH');
  const [publishBusy, setPublishBusy] = useState(false);
  const [loadError, setLoadError] = useState<string>();
  const [versionHistory, setVersionHistory] = useState<TemplateVersionHistoryItem[]>([]);
  const [picking, setPicking] = useState<{ fieldId: string; target: CoordinateTarget }>();
  const [groupManagerOpen, setGroupManagerOpen] = useState(false);
  const [groupDrafts, setGroupDrafts] = useState<FieldModel['groups']>([]);
  const [fieldManagerTab, setFieldManagerTab] = useState<FieldManagerTab>('structure');
  const [recognitionReview, setRecognitionReview] = useState<RecognitionReview>();
  const [recognitionActions, setRecognitionActions] = useState<Record<string, RecognitionAction>>(
    {},
  );
  const [recognitionAlternativeSelections, setRecognitionAlternativeSelections] = useState<Record<string, string>>({});
  const [qualityActions, setQualityActions] = useState<Record<string, QualityAction>>({});
  const [selectedQualityIssueId, setSelectedQualityIssueId] = useState<string>();
  const [recognitionJob, setRecognitionJob] = useState<TemplateImportJob>();
  const [recognitionBusy, setRecognitionBusy] = useState(false);
  const [structureOperations, setStructureOperations] = useState<WorkbookStructureOperation[]>([]);
  const [wordPatch, setWordPatch] = useState<WordPatchOperation[]>([]);
  const savedSnapshotSignatureRef = useRef<string>();
  const pendingEditorOperationsRef = useRef<Set<Promise<void>>>(new Set());
  const isDesktop = useDesktopEditing();

  useEffect(() => {
    if (!versionId) return;
    let active = true;
    const load = async () => {
      try {
        const model = await templateApi.getEditModel(versionId);
        const [history, review] = await Promise.all([
          templateApi.listVersions(model.templateId).catch(() => []),
            model.recognitionRunId
              ? templateApi.getRecognitionReview(model.versionId).catch(() => undefined)
            : Promise.resolve(undefined),
        ]);
        const loadedSnapshot =
          model.snapshotFileId && model.snapshotHash
            ? await templateApi.downloadSnapshot(model.snapshotFileId)
            : (model.inlineSnapshot ?? {});
        if (!active) return;
        const storedFieldModel = readFieldModel(model.schema, model.mapping);
        const merged = model.format === 'DOCX'
          ? { schema: normalizeWordSchema(model.schema), mapping: [] as TemplateBinding[], model: emptyWordFieldModel() }
          : review
            ? mergeRecognitionReview(model.schema, model.mapping, storedFieldModel, review)
            : { schema: model.schema, mapping: model.mapping, model: storedFieldModel };
        setWorkspace(model);
        setSchema(merged.schema);
        setMapping(merged.mapping);
        setFieldModel(merged.model);
        setSelectedFieldId(merged.model.fields.find((field) => !isRegionModelField(field))?.id);
        setRecognitionReview(model.format === 'DOCX' ? undefined : review);
        setReviewSyncState('FRESH');
        setQualityActions({});
        setSelectedQualityIssueId(
          model.format === 'DOCX'
            ? undefined
            : review?.qualityIssues.find((issue) => issue.severity === 'BLOCKER')?.id,
        );
        setFieldManagerTab(
          review?.recognitionRunId ? 'recognition' : 'structure',
        );
        setData(model.data ?? {});
        setSnapshot(loadedSnapshot);
        savedSnapshotSignatureRef.current = snapshotSignature(loadedSnapshot);
        setVersionHistory(history);
        setView(model.status === 'DRAFT' ? 'edit' : 'preview');
        setSaveState('SAVED');
        setStructureOperations([]);
      } catch (error) {
        if (active) setLoadError(error instanceof Error ? error.message : '工作台加载失败');
      }
    };
    void load();
    return () => {
      active = false;
    };
  }, [versionId]);

  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (['DIRTY', 'FAILED', 'CONFLICT'].includes(saveState)) event.preventDefault();
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [saveState]);

  useEffect(() => () => window.clearTimeout(unboundChangeTimerRef.current), []);

  const markDirty = useCallback(() => {
    setSaveState((current) => (current === 'SAVING' ? current : 'DIRTY'));
  }, []);

  const handleEditorDirty = useCallback(() => {
    if (workspace?.format === 'DOCX' && wordPatch.length > 0) {
      markDirty();
      return;
    }
    const currentSnapshot = editorRef.current?.getSnapshot();
    if (
      currentSnapshot &&
      savedSnapshotSignatureRef.current === snapshotSignature(currentSnapshot)
    ) {
      return;
    }
    markDirty();
  }, [markDirty, wordPatch.length, workspace?.format]);

  const waitForPendingEditorOperations = async () => {
    for (let attempt = 0; attempt < 4; attempt += 1) {
      const pending = [...pendingEditorOperationsRef.current];
      if (pending.length) await Promise.all(pending);
      await new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve()));
      if (!pendingEditorOperationsRef.current.size) return;
    }
  };

  const refreshRecognitionReview = async (
    notifyOnFailure = false,
    base?: { schema: Record<string, unknown>; mapping: TemplateBinding[]; model: FieldModel },
  ): Promise<boolean> => {
    if (!versionId || !workspace?.recognitionRunId) {
      setReviewSyncState('FRESH');
      return true;
    }
    setReviewSyncState('REFRESHING');
    try {
      const latestReview = await templateApi.getRecognitionReview(versionId);
      // A structure confirmation is applied on the server during save.  The
      // resulting REGION_FIELDS suggestions are therefore not present in the
      // previous client model; merge the refreshed review immediately so the
      // structure tab shows them as pending fields without requiring a reload.
      const merged = mergeRecognitionReview(
        base?.schema ?? schema,
        base?.mapping ?? mapping,
        base?.model ?? fieldModel,
        latestReview,
      );
      setRecognitionReview(latestReview);
      setSchema(merged.schema);
      setMapping(merged.mapping);
      setFieldModel(merged.model);
      setReviewSyncState('FRESH');
      return true;
    } catch (error) {
      setReviewSyncState('STALE');
      if (notifyOnFailure) {
        void message.warning(
          error instanceof Error
            ? `草稿已保存，但审核状态刷新失败：${error.message}`
            : '草稿已保存，但审核状态刷新失败，请稍后重试',
        );
      }
      return false;
    }
  };

  const handleEditorValue = useCallback((binding: TemplateBinding, value: unknown) => {
    if (value === undefined) return;
    setData((current) => {
      const previous = getAtPath(current, binding.dataPath);
      return JSON.stringify(previous) === JSON.stringify(value)
        ? current
        : setAtPath(current, binding.dataPath, value);
    });
    if (binding.mappingKind === 'REPEAT_FIELD' && hasMeaningfulTail(value, binding)) {
      const terminationType = stringValue(binding.termination?.type);
      const maxRecords = Number(binding.termination?.maxRecords);
      const recordCount = Array.isArray(value) ? value.length : 0;
      const limited =
        terminationType === 'FIXED_COUNT' &&
        Number.isFinite(maxRecords) &&
        maxRecords > 0 &&
        recordCount >= maxRecords;
      const parentKey = binding.parentBindingId || binding.bindingId;
      const rangeKey = stringValue(
        binding.locator.parentRange || binding.locator.dataRange || binding.locator.address,
      );
      const tailKey = `${parentKey}|${rangeKey}`;
      if (!limited && !autoAppendKeysRef.current.has(tailKey)) {
        autoAppendKeysRef.current.add(tailKey);
        const append = editorRef.current?.appendRepeatRecord?.(binding);
        if (append) {
          const pendingAppend = append
            .catch(() => {
              // Manual insertion remains available when the editor does not expose
              // the row/column insertion command for the current workbook version.
            })
            .finally(() => {
              pendingEditorOperationsRef.current.delete(pendingAppend);
              window.setTimeout(() => autoAppendKeysRef.current.delete(tailKey), 80);
            });
          pendingEditorOperationsRef.current.add(pendingAppend);
          void pendingAppend;
        } else {
          autoAppendKeysRef.current.delete(tailKey);
        }
      }
    }
  }, []);

  const save = async (recognitionOverride?: {
    recognitionItemId: string;
    action: RecognitionAction;
    selectedAlternativeId?: string;
  }): Promise<boolean> => {
    if (!workspace || !versionId || !editorRef.current) return false;
    const unnamed = workspace.format === 'XLSX'
      ? fieldModel.fields.find((field) => !(field.name ?? '').trim())
      : undefined;
    if (unnamed) {
      setSelectedFieldId(unnamed.id);
      void message.warning('请先填写字段名称');
      return false;
    }
    setSaveState('SAVING');
    try {
      await waitForPendingEditorOperations();
      const currentSnapshot = editorRef.current.getSnapshot();
      const formalMapping = workspace.format === 'DOCX'
        ? []
        : prepareFormalMappings(mapping, fieldModel);
      const synchronized = workspace.format === 'DOCX'
        ? { data, bindingValues: [] as BindingValuePair[] }
        : synchronizeStructuredData(
            data,
            formalMapping,
            (binding) => editorRef.current?.readBinding(binding),
          );
      const synchronizedData = synchronized.data;
      const bindingValues = synchronized.bindingValues;
      const staged = await templateApi.stageSnapshot(currentSnapshot, workspace.format);
      const savedSchema = workspace.format === 'DOCX'
        ? normalizeWordSchema(schema)
        : prepareFormalSchema(schema, fieldModel);
      const effectiveRecognitionActions = {
        ...recognitionActions,
        ...(recognitionOverride
          ? { [recognitionOverride.recognitionItemId]: recognitionOverride.action }
          : {}),
      };
      const effectiveAlternativeSelections = {
        ...recognitionAlternativeSelections,
        ...(recognitionOverride?.selectedAlternativeId
          ? { [recognitionOverride.recognitionItemId]: recognitionOverride.selectedAlternativeId }
          : {}),
      };
      const result = await templateApi.saveDraft(versionId, {
        lockVersion: workspace.lockVersion,
        baseWorkspaceHash: workspace.workspaceHash,
        schema: savedSchema,
        mapping: formalMapping,
        data: synchronizedData,
        snapshotFileId: staged?.fileId ?? workspace.snapshotFileId,
        snapshotHash: staged?.sha256 ?? workspace.snapshotHash,
         editorAppVersion: workspace.format === 'DOCX' ? 'univer-docs-0.25.1' : 'univer-0.25.1',
         pluginManifest: workspace.format === 'DOCX' ? 'word-document-v1' : 'business-editor-v2',
        snapshotFormatVersion: snapshotVersion(currentSnapshot, workspace.snapshotFormatVersion),
         clientCommandSummary: workspace.format === 'DOCX'
           ? 'word-document-save'
           : `field-position-save:${fieldModel.fields.length}`,
        idempotencyKey: crypto.randomUUID(),
        bindingValues,
        recognitionActions: Object.entries(effectiveRecognitionActions).map(
          ([recognitionItemId, action]) => ({
            recognitionItemId,
            action,
            ...(effectiveAlternativeSelections[recognitionItemId]
              ? { selectedAlternativeId: effectiveAlternativeSelections[recognitionItemId] }
              : {}),
          }),
        ),
        qualityActions: Object.entries(qualityActions).map(([issueId, action]) => ({
          issueId,
          action,
        })),
        structureOperations,
        wordPatch: wordPatch as unknown as Array<Record<string, unknown>>,
        wordPatchBaseHash: workspace.wordDocument?.documentHash,
      });
      savedSnapshotSignatureRef.current = snapshotSignature(currentSnapshot);
      setData(synchronizedData);
      const persistedSchema = result.schema ?? savedSchema;
      const persistedMapping = result.mapping ?? formalMapping;
      setSchema(persistedSchema);
      setMapping(persistedMapping);
      setWorkspace({
        ...workspace,
        lockVersion: result.lockVersion,
        workspaceHash: result.workspaceHash,
        snapshotFileId: staged?.fileId ?? workspace.snapshotFileId,
        snapshotHash: staged?.sha256 ?? workspace.snapshotHash,
        reconciliationRequired: result.reconciliationRequired,
        wordDocument: result.wordDocument ?? workspace.wordDocument,
        documentStructure: result.documentStructure ?? workspace.documentStructure,
      });
      setSnapshot(currentSnapshot);
      setRecognitionActions({});
      setRecognitionAlternativeSelections({});
      setQualityActions({});
      setStructureOperations([]);
      setWordPatch([]);
      const reviewRefreshed = workspace.format === 'DOCX'
        ? true
        : await refreshRecognitionReview(true, {
            schema: persistedSchema,
            mapping: persistedMapping,
            model: fieldModel,
          });
      if (workspace.format === 'DOCX') {
        setMapping([]);
        setFieldModel(emptyWordFieldModel());
        setRecognitionReview(undefined);
      }
      setSaveState('SAVED');
      if (reviewRefreshed) {
        void message.success(
          recognitionOverride
            ? '结构已确认，字段识别结果已刷新；字段仍需逐项确认'
            : '模板草稿已保存',
        );
      }
      return true;
    } catch (error) {
      if (error instanceof HttpError && error.code === 'OPTIMISTIC_LOCK_CONFLICT') {
        setSaveState('CONFLICT');
        modal.confirm({
          title: '草稿已在其他位置更新',
          content: '本地内容仍然保留。重新加载后可以继续处理，系统不会覆盖其他人的修改。',
          okText: '重新加载',
          cancelText: '先保留本地内容',
          onOk: () => window.location.reload(),
        });
      } else {
        setSaveState('FAILED');
        // The recognition action is transactional on the server.  If the
        // save failed, discard optimistic conflict/confirmation state and
        // re-read the persisted review so the panel cannot claim a structure
        // was accepted while REGION_FIELDS was rolled back.
        try {
          const persistedReview = await templateApi.getRecognitionReview(versionId);
          setRecognitionReview(persistedReview);
          setRecognitionActions({});
          setRecognitionAlternativeSelections({});
        } catch {
          // Keep the local draft visible when the recovery request itself is unavailable.
        }
        void message.error(saveErrorMessage(error));
      }
      return false;
    }
  };

  function saveErrorMessage(error: unknown) {
    const raw = error instanceof Error ? error.message : '';
    if (raw.includes('dataPath 必须位于父级记录下')) {
      return '明细字段的记录位置与所属明细区域不一致，未保存本次修改；请重新打开该明细字段并保存。';
    }
    if (raw.includes('Mapping 缺少 bindingId') || raw.includes('Mapping 缺少 dataPath') || raw.includes('Mapping 缺少 locator')) {
      return '有字段还没有完整的填写位置，未保存本次修改；请在字段属性中补充填写位置。';
    }
    return raw || '保存失败，本地内容仍然保留';
  }

  const publishNow = async () => {
    if (!workspace || !versionId) return;
    setPublishBusy(true);
    try {
      await templateApi.publish(versionId);
      setWorkspace({ ...workspace, status: 'PUBLISHED' });
      setView('preview');
      void message.success('模板已发布，当前版本已锁定');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '发布失败');
    } finally {
      setPublishBusy(false);
    }
  };

  const exportTemplate = async (targetVersionId: string, state: 'DRAFT' | 'PUBLISHED') => {
    if (!workspace) return;
    if (state === 'DRAFT' && saveState !== 'SAVED') {
      if (!(await save())) return;
    }
    try {
      const checked = await templateApi.checkExport(targetVersionId, workspace.format, state);
      const download = async () => {
        await templateApi.exportOffice(targetVersionId, workspace.format, state);
        void message.success('模板文件已开始下载');
      };
      if (checked.warnings.length) {
        modal.confirm({
          title: `导出包含 ${checked.warnings.length} 个提示`,
          content: checked.warnings.slice(0, 5).map((item) => item.message).join('；'),
          okText: '继续导出', cancelText: '取消',
          onOk: () => download().catch((error) => void message.error(error instanceof Error ? error.message : '模板导出失败')),
        });
      } else {
        await download();
      }
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '模板导出失败');
    }
  };

  const requestPublish = () => {
    if (!workspace || workspace.status !== 'DRAFT') return;
    if (workspace.format === 'DOCX') {
      void (async () => {
        if (saveState !== 'SAVED' && !(await save())) return;
        await publishNow();
      })();
      return;
    }
    const readinessBlocker = publishReadinessBlocker(saveState, reviewSyncState);
    if (readinessBlocker) {
      setFieldManagerTab('recognition');
      void message.warning(readinessBlocker);
      if (reviewSyncState === 'STALE') void refreshRecognitionReview(true);
      return;
    }
    if ((recognitionReview?.summary.conflict ?? 0) > 0) {
      setFieldManagerTab('recognition');
      void message.warning('识别结果仍有冲突，请处理后再发布');
      return;
    }
    if ((recognitionReview?.summary.blockingIssueCount ?? 0) > 0) {
      setFieldManagerTab('recognition');
      void message.warning('模板仍有会影响可靠填写的问题，请按识别确认中的提示处理');
      return;
    }
    const missingRequired = fieldsMissingRequiredPosition(fieldModel, mapping, workspace.format);
    if (missingRequired.length) {
      setSelectedFieldId(missingRequired[0]?.id);
      void message.warning(`必填字段“${missingRequired[0]?.name}”还没有有效填写位置`);
      return;
    }
    const warnings = nonBlockingWarnings(fieldModel, mapping, workspace.format);
    if (!warnings.length) {
      void publishNow();
      return;
    }
    modal.confirm({
      title: '确认发布当前模板？',
      content: (
        <div className="publish-warning-list">
          <p>以下内容不会阻止发布，建议确认后继续：</p>
          {warnings.map((warning) => (
            <div key={warning}>· {warning}</div>
          ))}
        </div>
      ),
      okText: '继续发布',
      cancelText: '返回检查',
      onOk: publishNow,
    });
  };

  const findFieldForRecognitionItem = (item: RecognitionReviewItem) =>
    fieldModel.fields.find((field) => fieldMatchesIdentity(field, {
      bindingId: item.payload.bindingId,
      relationId: item.payload.relationId,
      fieldId: item.payload.fieldId,
      recognitionItemId: item.id,
    }));

  const focusRecognitionItem = (item: RecognitionReviewItem) => {
    setSelectedRecognitionItemId(item.id);
    const field = findFieldForRecognitionItem(item);
    if (field) {
      setSelectedFieldId(field.id);
      const binding =
        mapping.find((candidate) => bindingMatchesIdentity(candidate, {
          bindingId: field.bindingId,
          relationId: field.relationId,
          fieldId: field.fieldId || field.id,
        })) ??
        candidateBinding(field);
      if (binding) editorRef.current?.focusBinding(binding);
      return;
    }
    editorRef.current?.focusBinding({
      bindingId: item.id,
      dataPath: item.payload.dataPath,
      role: item.kind === 'SCALAR' ? 'FIELD' : 'REPEAT_REGION',
      locatorType: item.payload.locatorType,
      locator: item.payload.locator,
      syncDirection: item.payload.editability === 'READ_ONLY' ? 'EDITOR_TO_DATA' : 'TWO_WAY',
      primaryBinding: true,
      bindingStatus:
        item.payload.editability === 'UNKNOWN' || item.payload.valueSource === 'UNKNOWN'
          ? 'AMBIGUOUS'
          : 'VALID',
    });
  };

  const focusQualityIssue = (issue: TemplateQualityIssue) => {
    setSelectedQualityIssueId(issue.id);
    const rawOperations: unknown = issue.suggestedPatch?.operations;
    const patchOperations = Array.isArray(rawOperations)
      ? rawOperations.filter(
          (operation): operation is Record<string, unknown> =>
            isRecord(operation) && operation.op === 'SET_CELL',
        )
      : [];
    const titleAddress =
      typeof patchOperations[0]?.address === 'string'
        ? patchOperations[0].address
        : issue.address.split(':')[0];
    const contentAddress =
      typeof patchOperations[1]?.address === 'string' ? patchOperations[1].address : issue.address;
    editorRef.current?.focusBinding({
      bindingId: `quality-${issue.id}`,
      dataPath: `/qualityIssues/${issue.id}`,
      role: 'FIELD',
      locatorType: 'CELL_RANGE',
      locator: {
        sheetId: issue.sheetId,
        sheetName: issue.sheetName,
        labelAddress: titleAddress,
        labelRange: titleAddress,
        anchorAddress: contentAddress.split(':')[0],
        logicalInputRange: contentAddress,
        address: contentAddress,
      },
      syncDirection: 'EDITOR_TO_DATA',
      primaryBinding: false,
      bindingStatus: 'VALID',
    });
  };

  const updateQualityIssueStatus = (
    issueId: string,
    status: TemplateQualityIssue['status'],
    action: QualityAction,
  ) => {
    setRecognitionReview((current) =>
      current
        ? {
            ...current,
            qualityIssues: current.qualityIssues.map((issue) =>
              issue.id === issueId ? { ...issue, status } : issue,
            ),
            summary: {
              ...current.summary,
              autoFixedCount: current.qualityIssues.filter(
                (issue) => (issue.id === issueId ? status : issue.status) === 'AUTO_APPLIED',
              ).length,
              blockingIssueCount: current.qualityIssues.filter((issue) => {
                const nextStatus = issue.id === issueId ? status : issue.status;
                return (
                  issue.severity === 'BLOCKER' &&
                  !['AUTO_APPLIED', 'CONFIRMED', 'IGNORED'].includes(nextStatus)
                );
              }).length,
            },
          }
        : current,
    );
    setQualityActions((current) => ({ ...current, [issueId]: action }));
    markDirty();
  };

  const applyQualityIssue = async (issue: TemplateQualityIssue) => {
    try {
      await editorRef.current?.applyCellPatch?.(issue.suggestedPatch ?? {});
      updateQualityIssueStatus(issue.id, 'CONFIRMED', 'APPLY');
      focusQualityIssue(issue);
      void message.success('已应用规范建议，请保存草稿');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '规范建议未能应用');
    }
  };

  const rollbackQualityIssue = async (issue: TemplateQualityIssue) => {
    try {
      await editorRef.current?.applyCellPatch?.(issue.inversePatch ?? {});
      updateQualityIssueStatus(issue.id, 'ROLLED_BACK', 'ROLLBACK');
      focusQualityIssue(issue);
      void message.success('已撤销自动规范，请保存草稿');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '自动规范未能撤销');
    }
  };

  const ignoreQualityIssue = (issue: TemplateQualityIssue) => {
    updateQualityIssueStatus(issue.id, 'IGNORED', 'IGNORE');
  };

  const recordRecognitionAction = (
    recognitionItemId: string | undefined,
    action: RecognitionAction,
    status: RecognitionReviewItem['status'],
    selectedAlternativeId?: string,
  ) => {
    if (!recognitionItemId) return;
    setRecognitionActions((current) => ({ ...current, [recognitionItemId]: action }));
    if (selectedAlternativeId) {
      setRecognitionAlternativeSelections((current) => ({
        ...current,
        [recognitionItemId]: selectedAlternativeId,
      }));
    }
    setRecognitionReview((current) =>
      current ? updateRecognitionReview(current, new Map([[recognitionItemId, status]])) : current,
    );
  };

  const handleEditorLabel = (binding: TemplateBinding, value: unknown) => {
    const field = fieldModel.fields.find((item) => fieldMatchesIdentity(item, {
      bindingId: binding.bindingId,
      relationId: binding.relationId,
      fieldId: binding.fieldId,
    }));
    if (!field) return;
    const name = editorLabelText(value);
    if (field.name === name) return;

    const updated = updateBusinessField(schema, fieldModel, field.id, {
      name,
      reviewStatus: 'CONFIRMED',
    });
    setSchema(updated.schema);
    setFieldModel(updated.model);
          setMapping((current) =>
      current.map((item) =>
        bindingMatchesIdentity(item, {
          bindingId: binding.bindingId,
          relationId: binding.relationId,
          fieldId: binding.fieldId,
        })
          ? {
              ...item,
              ...(workspace?.format === 'DOCX'
                ? { locator: { ...item.locator, text: name } }
                : {}),
              diagnostic: { ...item.diagnostic, displayName: name },
            }
          : item,
      ),
    );
    setRecognitionReview((current) => {
      if (!current) return current;
      const matchingItem = current.items.find(
        (item) => fieldMatchesIdentity(field, {
          bindingId: item.payload.bindingId,
          relationId: item.payload.relationId,
          fieldId: item.payload.fieldId,
          recognitionItemId: item.id,
        }),
      );
      if (!matchingItem) return current;
      const renamed = {
        ...current,
        items: current.items.map((item) =>
          item.id === matchingItem.id
            ? {
                ...item,
                fieldName: name,
                payload: { ...item.payload, fieldName: name },
              }
            : item,
        ),
      };
      return updateRecognitionReview(renamed, new Map([[matchingItem.id, 'CONFIRMED']]));
    });
    if (field.recognitionItemId) {
      setRecognitionActions((current) => ({
        ...current,
        [field.recognitionItemId as string]: 'CONFIRM',
      }));
    }
  };

  const confirmRecognitionItem = async (item: RecognitionReviewItem, selectedAlternativeId?: string) => {
    if (item.payload.structureAlternatives?.length || requiresServerStructureConfirmation(item)) {
      recordRecognitionAction(item.id, 'CONFIRM', 'CONFIRMED', selectedAlternativeId);
      markDirty();
      setRecognitionBusy(true);
      const pending = message.loading('正在确认结构并批量识别字段…', 0);
      try {
        await save({
          recognitionItemId: item.id,
          action: 'CONFIRM',
          selectedAlternativeId,
        });
      } finally {
        pending();
        setRecognitionBusy(false);
      }
      return;
    }
    const accepted = acceptRecognitionReviewItem(schema, mapping, fieldModel, item);
    setSchema(accepted.schema);
    setMapping(accepted.mapping);
    setFieldModel(accepted.model);
    recordRecognitionAction(item.id, 'CONFIRM', 'CONFIRMED', selectedAlternativeId);
    markDirty();
    void message.success('字段已记录，点击“保存草稿”后写入服务器');
    const next = nextRecognitionItem(recognitionReview, item.id);
    if (next) window.requestAnimationFrame(() => focusRecognitionItem(next));
  };

  const ignoreRecognitionItem = (item: RecognitionReviewItem) => {
    const field = findFieldForRecognitionItem(item);
    if (field) {
      const removed = removeBusinessField(schema, fieldModel, field.id);
      setSchema(removed.schema);
      setFieldModel(removed.model);
      setMapping((current) => current.filter((binding) => binding.bindingId !== field.bindingId));
      setSelectedFieldId(removed.model.fields[0]?.id);
    }
    recordRecognitionAction(item.id, 'IGNORE', 'IGNORED');
    markDirty();
  };

  const restoreRecognitionItem = (item: RecognitionReviewItem) => {
    if (!recognitionReview) return;
    const restoredReview = updateRecognitionReview(
      recognitionReview,
      new Map([[item.id, 'PENDING']]),
    );
    const merged = mergeRecognitionReview(schema, mapping, fieldModel, restoredReview);
    setSchema(merged.schema);
    setMapping(merged.mapping);
    setFieldModel(merged.model);
    setRecognitionReview(restoredReview);
    setRecognitionActions((current) => ({ ...current, [item.id]: 'RESTORE' }));
    const restored = merged.model.fields.find((field) => fieldMatchesIdentity(field, {
      bindingId: item.payload.bindingId,
      relationId: item.payload.relationId,
      fieldId: item.payload.fieldId,
      recognitionItemId: item.id,
    }));
    if (restored) setSelectedFieldId(restored.id);
    markDirty();
  };

  const modifyRecognitionItem = (item: RecognitionReviewItem) => {
    focusRecognitionItem(item);
    setFieldManagerTab('properties');
  };

  const beginRecognitionReview = () => {
    setFieldManagerTab('recognition');
    const next = nextRecognitionItem(recognitionReview);
    if (next) window.requestAnimationFrame(() => focusRecognitionItem(next));
  };

  const confirmHighConfidence = () => {
    if (!recognitionReview) return;
    const targets = recognitionReview.items.filter(
      (item) =>
        item.status === 'PENDING' &&
        item.confidence >= 0.85 &&
        !item.payload.requiresStandardConfirmation &&
        !item.payload.semanticConflict &&
        !item.payload.candidateOnly &&
        !item.payload.reviewRequired &&
        !item.payload.physicalStructureOnly &&
        !item.payload.structureConflict &&
        !item.payload.protocolRecovery,
    );
    if (!targets.length) {
      void message.info('没有可直接确认的识别项目');
      return;
    }
    const statusUpdates = new Map(targets.map((item) => [item.id, 'CONFIRMED' as const]));
    setRecognitionReview(updateRecognitionReview(recognitionReview, statusUpdates));
    setRecognitionActions((current) => ({
      ...current,
      ...Object.fromEntries(targets.map((item) => [item.id, 'CONFIRM' as const])),
    }));
    let nextSchema = schema;
    let nextMapping = mapping;
    let nextModel = fieldModel;
    for (const item of targets) {
      const accepted = acceptRecognitionReviewItem(nextSchema, nextMapping, nextModel, item);
      nextSchema = accepted.schema;
      nextMapping = accepted.mapping;
      nextModel = accepted.model;
    }
    setSchema(nextSchema);
    setMapping(nextMapping);
    setFieldModel(nextModel);
    markDirty();
    void message.success(`已确认 ${targets.length} 个识别项目`);
  };

  const restartRecognition = async () => {
    if (!workspace || !versionId || recognitionBusy) return;
    if (saveState !== 'SAVED' || !workspace.snapshotFileId) {
      const saved = await save();
      if (!saved) return;
    }
    setRecognitionBusy(true);
    setFieldManagerTab('recognition');
    try {
      const started = await templateApi.restartRecognition(versionId);
      setRecognitionJob(started);
      let latest = started;
      for (let attempt = 0; attempt < 120; attempt++) {
        if (['PARSED', 'FAILED'].includes(latest.status)) break;
        await delay(1000);
        latest = await templateApi.getImport(started.id);
        setRecognitionJob(latest);
      }
      if (latest.status !== 'PARSED') {
        throw new Error(latest.lastError || '重新识别未能完成，请稍后重试');
      }
      const review = await templateApi.getRecognitionReview(versionId);
      const merged = mergeRecognitionReview(schema, mapping, fieldModel, review);
      setRecognitionReview(review);
      setRecognitionActions({});
      setQualityActions({});
      setSchema(merged.schema);
      setMapping(merged.mapping);
      setFieldModel(merged.model);
      setSelectedFieldId(merged.model.fields.find((field) => !isRegionModelField(field))?.id);
      setWorkspace((current) =>
        current ? { ...current, recognitionRunId: review.recognitionRunId } : current,
      );
      markDirty();
      if (latest.result.initialEditorSnapshot) {
        setSnapshot(latest.result.initialEditorSnapshot);
      }
      if (review.runStatus === 'FAILED') {
        void message.warning('智能识别未完成，工作簿和原有字段已保留，可稍后重试');
      } else {
        void message.success('重新识别完成，请查看识别结果并保存草稿');
      }
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '重新识别失败');
    } finally {
      setRecognitionBusy(false);
    }
  };

  const selectField = (field: BusinessField) => {
    setSelectedFieldId(field.id);
    setSelectedRecognitionItemId(
      field.recognitionItemId ??
        recognitionReview?.items.find((item) => fieldMatchesIdentity(field, {
          bindingId: item.payload.bindingId,
          relationId: item.payload.relationId,
          fieldId: item.payload.fieldId,
        }))?.id,
    );
    const binding =
      mapping.find((item) => bindingMatchesIdentity(item, {
        bindingId: field.bindingId,
        relationId: field.relationId,
        fieldId: field.fieldId || field.id,
      })) ??
      candidateBinding(field) ??
      (field.locator
        ? {
            bindingId: `field-${field.id}`,
            fieldId: field.id,
            dataPath: field.dataPath || '',
            role: 'FIELD' as const,
            locatorType: 'CELL_RANGE',
            locator: field.locator,
            syncDirection: 'TWO_WAY' as const,
            primaryBinding: false,
            bindingStatus: 'VALID' as const,
          }
        : undefined);
    if (binding) editorRef.current?.focusBinding(binding);
  };

  const updateField = (fieldId: string, update: Partial<BusinessField>) => {
    let currentField = fieldModel.fields.find((field) => field.id === fieldId);
    if (!currentField) return;
    let baseSchema = schema;
    let baseMapping = mapping;
    let baseModel = fieldModel;
    if (currentField.candidate) {
      const reviewItem = recognitionReview?.items.find(
        (item) => fieldMatchesIdentity(currentField as BusinessField, {
          bindingId: item.payload.bindingId,
          relationId: item.payload.relationId,
          fieldId: item.payload.fieldId,
          recognitionItemId: item.id,
        }),
      );
      if (!reviewItem) return;
      const accepted = acceptRecognitionReviewItem(schema, mapping, fieldModel, reviewItem);
      baseSchema = accepted.schema;
      baseMapping = accepted.mapping;
      baseModel = accepted.model;
      currentField = baseModel.fields.find((field) => fieldMatchesIdentity(field, {
        bindingId: reviewItem.payload.bindingId,
        relationId: reviewItem.payload.relationId,
        fieldId: reviewItem.payload.fieldId,
        recognitionItemId: reviewItem.id,
      }));
      if (!currentField) return;
    }
    const resolvesConflict = Boolean(
      currentField.semanticConflict && (update.fieldCode || update.name),
    );
    const normalizedUpdate = {
      ...update,
      ...(resolvesConflict
        ? { semanticConflict: false, conflictCode: undefined, conflictMessage: undefined }
        : {}),
      reviewStatus: 'CONFIRMED' as const,
    };
    const updated = updateBusinessField(baseSchema, baseModel, currentField.id, normalizedUpdate);
    setSchema(updated.schema);
    setFieldModel(updated.model);
    const updatedMapping = baseMapping.map((binding) =>
      bindingMatchesIdentity(binding, {
        bindingId: currentField.bindingId,
        relationId: currentField.relationId,
        fieldId: currentField.fieldId || currentField.id,
      })
        ? {
            ...binding,
            ...(Object.prototype.hasOwnProperty.call(update, 'fieldCode')
              ? { fieldCode: update.fieldCode }
              : {}),
            diagnostic: {
              ...binding.diagnostic,
              displayName: update.name ?? currentField.name,
              groupName: updated.model.groups.find(
                (group) => group.id === (update.groupId ?? currentField.groupId),
              )?.name,
              description: update.description ?? currentField.description,
              ...(Object.prototype.hasOwnProperty.call(update, 'standardFieldId')
                ? { standardFieldId: update.standardFieldId }
                : {}),
              ...(Object.prototype.hasOwnProperty.call(update, 'standardFieldVersion')
                ? { standardFieldVersion: update.standardFieldVersion }
                : {}),
              ...(Object.prototype.hasOwnProperty.call(update, 'standardFieldName')
                ? { standardFieldName: update.standardFieldName }
                : {}),
              ...(Object.prototype.hasOwnProperty.call(update, 'fieldOrigin')
                ? { fieldOrigin: update.fieldOrigin }
                : {}),
              ...(Object.prototype.hasOwnProperty.call(update, 'standardSelectionStatus')
                ? { standardSelectionStatus: update.standardSelectionStatus }
                : {}),
              ...(Object.prototype.hasOwnProperty.call(update, 'standardMatchStatus')
                ? { standardMatchStatus: update.standardMatchStatus }
                : {}),
              ...(Object.prototype.hasOwnProperty.call(update, 'requiresStandardConfirmation')
                ? { requiresStandardConfirmation: update.requiresStandardConfirmation }
                : {}),
              semanticConflict:
                updated.model.fields.find((field) => field.id === currentField.id)
                  ?.semanticConflict ?? false,
            },
          }
        : binding,
    );
    setMapping(updatedMapping);
    if (update.name !== undefined && update.name !== currentField.name) {
      const binding = updatedMapping.find((item) => bindingMatchesIdentity(item, {
        bindingId: currentField.bindingId,
        relationId: currentField.relationId,
        fieldId: currentField.fieldId || currentField.id,
      }));
      if (binding) {
        void editorRef.current?.writeLabel?.(binding, update.name).catch((error) => {
          void message.error(error instanceof Error ? error.message : '字段名称未能写入 Excel');
        });
      }
    }
    recordRecognitionAction(currentField.recognitionItemId, 'CONFIRM', 'CONFIRMED');
    markDirty();
  };

  const updateCoordinates = (
    fieldId: string,
    update: Partial<Record<CoordinateTarget, string>>,
    sheet?: Pick<EditorSelection, 'sheetId' | 'sheetName'>,
  ) => {
    let field = fieldModel.fields.find((item) => item.id === fieldId);
    if (!field) return;
    let baseMapping = mapping;
    let baseModel = fieldModel;
    let baseSchema = schema;
    if (field.candidate) {
      const reviewItem = recognitionReview?.items.find(
        (item) => fieldMatchesIdentity(field as BusinessField, {
          bindingId: item.payload.bindingId,
          relationId: item.payload.relationId,
          fieldId: item.payload.fieldId,
          recognitionItemId: item.id,
        }),
      );
      if (!reviewItem) return;
      const accepted = acceptRecognitionReviewItem(schema, mapping, fieldModel, reviewItem);
      baseMapping = accepted.mapping;
      baseModel = accepted.model;
      baseSchema = accepted.schema;
      field = baseModel.fields.find((item) => fieldMatchesIdentity(item, {
        bindingId: reviewItem.payload.bindingId,
        relationId: reviewItem.payload.relationId,
        fieldId: reviewItem.payload.fieldId,
        recognitionItemId: reviewItem.id,
      }));
    }
    if (!field) return;
    const fieldIdentity = {
      bindingId: field.bindingId,
      relationId: field.relationId,
      fieldId: field.fieldId || field.id,
    };
    const currentBinding = baseMapping.find((binding) =>
      bindingMatchesIdentity(binding, fieldIdentity),
    );
    if (!currentBinding) return;
    const nextMapping: TemplateBinding[] = baseMapping.map((binding) => {
      if (!bindingMatchesIdentity(binding, fieldIdentity)) return binding;
      const baseLocator: Record<string, unknown> = {
        ...binding.locator,
        ...update,
        ...(update.labelAddress !== undefined ? { labelRange: update.labelAddress } : {}),
        ...(sheet ?? {}),
      };
      const locator: Record<string, unknown> =
        update.address !== undefined
          ? reflowStructuredLocator(baseLocator, field.kind, binding.locator)
          : baseLocator;
      if (update.address !== undefined && !locator.logicalInputRange) {
        locator.logicalInputRange = locator.address;
      }
      const address = stringValue(locator.address);
      const labelAddress = stringValue(locator.labelAddress);
      const invalid = Boolean(
        validateAddress(address, false) || validateAddress(labelAddress, true),
      );
      return {
        ...binding,
        locator,
        bindingStatus: invalid
          ? ('INVALID' as const)
          : field.required && !address
            ? ('MISSING' as const)
            : ('VALID' as const),
      };
    });
    const nextBinding = nextMapping.find((binding) => bindingMatchesIdentity(binding, fieldIdentity));
    if (!nextBinding) return;
    const nextLocator = structuredClone(nextBinding.locator);
    const nextAddress = stringValue(nextLocator.address || nextLocator.range);
    const nextLabelAddress = stringValue(nextLocator.labelAddress || nextLocator.labelRange);
    const modelUpdate: Partial<BusinessField> = {
      locator: nextLocator,
      labelRange: nextLabelAddress || undefined,
      valueRange: nextAddress || undefined,
      reviewStatus: nextBinding.bindingStatus === 'INVALID' ? 'ISSUE' : 'CONFIRMED',
      ...(field.kind === 'MATRIX' ? { matrixModel: matrixModelFromLocator(nextLocator) } : {}),
    };
    const updated = updateBusinessField(baseSchema, baseModel, field.id, modelUpdate);
    setSchema(updated.schema);
    setMapping(nextMapping);
    setFieldModel(updated.model);
    recordRecognitionAction(field.recognitionItemId, 'CONFIRM', 'CONFIRMED');
    markDirty();
  };

  const handleSelection = useCallback(
    (selection: EditorSelection) => {
      if (picking) {
        const address =
          picking.target === 'labelAddress'
            ? (selection.address.split(':')[0] ?? selection.address)
            : selection.address;
        updateCoordinates(
          picking.fieldId,
          { [picking.target]: normalizeAddress(address) },
          { sheetId: selection.sheetId, sheetName: selection.sheetName },
        );
        setSelectedFieldId(picking.fieldId);
        setPicking(undefined);
        void message.success('单元格位置已更新');
        return;
      }
      const key = selectionCycleKey(selection);
      const nextIndex =
        selectionCycleRef.current.key === key ? selectionCycleRef.current.index + 1 : 0;
      const selectableBindings = [
        ...mapping,
        ...fieldModel.fields.flatMap((field) => {
          const binding = candidateBinding(field);
          return binding ? [binding] : [];
        }),
      ];
      const match = resolveBindingSelection(selectableBindings, selection, nextIndex);
      if (!match) return;
      selectionCycleRef.current = { key, index: match.candidateIndex };
      const field = fieldModel.fields.find(
        (item) => fieldMatchesIdentity(item, {
          bindingId: match.binding.bindingId,
          relationId: match.binding.relationId,
          fieldId: match.binding.fieldId,
        }),
      );
      if (field) {
        setSelectedFieldId(field.id);
        setSelectedRecognitionItemId(
          field.recognitionItemId ??
            recognitionReview?.items.find((item) => fieldMatchesIdentity(field, {
              bindingId: item.payload.bindingId,
              relationId: item.payload.relationId,
              fieldId: item.payload.fieldId,
            }))?.id,
        );
      }
    },
    [fieldModel.fields, mapping, message, picking, recognitionReview?.items],
  );

  const handleUnboundCellChange = useCallback(
    (selection: EditorSelection, value: unknown) => {
      if (!versionId) return;
      if (!isSingleCellAddress(selection.address)) return;
      // A structural region binding only describes the envelope. It must not
      // block discovering an unbound scalar label inside a FORM_REGION. A
      // concrete FIELD binding, however, remains protected from duplication.
      const selectionMatch = resolveBindingSelection(mapping, selection);
      if (selectionMatch?.binding.role === 'FIELD') return;
      const label = potentialFieldLabel(value);
      const labelAddress = selection.address.split(':')[0] ?? selection.address;
      window.clearTimeout(unboundChangeTimerRef.current);
      if (!label) return;
      unboundChangeTimerRef.current = window.setTimeout(() => {
        if (
          mapping.some(
            (binding) =>
              stringValue(binding.locator.sheetId) === selection.sheetId &&
              normalizeAddress(stringValue(binding.locator.labelAddress)) ===
                normalizeAddress(labelAddress),
          )
        )
          return;
        if (
          fieldModel.fields.some((field) => {
            const locator = field.locator ?? {};
            return stringValue(locator.sheetId) === selection.sheetId
              && normalizeAddress(stringValue(locator.labelAddress || field.labelRange)) === normalizeAddress(labelAddress);
          })
        ) return;
        const id = crypto.randomUUID();
        const bindingId = crypto.randomUUID();
        const region = findFormRegionForCell(recognitionReview, selection.sheetId, labelAddress);
        const group =
          fieldModel.groups.find((item) => item.groupCode === 'BASIC_INFORMATION') ??
          fieldModel.groups[0];
        const groupId = group?.id ?? 'group-basic';
        const dataPath = `/customFields/field_${id.replaceAll('-', '')}`;
        const field: BusinessField = {
          id,
          bindingId,
          dataPath,
          groupId,
          name: label,
          kind: 'SCALAR',
          valueType: 'string',
          required: false,
          reviewStatus: 'NEEDS_CONFIRMATION',
          confidence: 0.6,
          interpretation: `系统发现了新标签“${label}”，请核对填写位置。`,
          fieldCode: templateLocalFieldCode(versionId, { id, name: label, dataPath }),
          fieldOrigin: 'TEMPLATE_LOCAL',
          standardSelectionStatus: 'CUSTOM',
          standardMatchStatus: 'UNMATCHED',
          requiresStandardConfirmation: false,
          editability: 'EDITABLE',
          valueSource: 'USER_INPUT',
          regionId: region?.regionId,
          blockId: region?.blockId,
          parentBlockId: region?.blockId,
        };
        setFieldModel((current) =>
          normalizeFieldModel({
            ...current,
            groups: current.groups.length
              ? current.groups
              : [
                  {
                    id: groupId,
                    name: '基础信息',
                    groupCode: 'BASIC_INFORMATION',
                    order: 0,
                  },
                ],
            fields: [...current.fields, field],
          }),
        );
        setSchema((current) => addCustomFieldSchema(current, field));
        setMapping((current) => [
          ...current,
          {
            bindingId,
            fieldCode: field.fieldCode,
            dataPath,
            role: 'FIELD',
            locatorType: 'CELL_RANGE',
            locator: {
              sheetId: selection.sheetId,
              sheetName: selection.sheetName,
              labelAddress,
              labelRange: labelAddress,
              anchorAddress: '',
              logicalInputRange: '',
              address: '',
              valueMode: 'ANCHOR',
            },
            syncDirection: 'TWO_WAY',
            primaryBinding: true,
            bindingStatus: 'MISSING',
            diagnostic: {
              source: 'LIVE_DISCOVERED',
              displayName: label,
              regionId: region?.regionId,
              blockId: region?.blockId,
            },
          },
        ]);
        setSelectedFieldId(id);
        setFieldManagerTab('structure');
        markDirty();
        void message.info(`已发现新字段“${label}”，请核对填写位置`);
      }, 800);
    },
    [fieldModel.fields, fieldModel.groups, mapping, markDirty, message, recognitionReview, versionId],
  );

  const handleStructureChange = useCallback(
    (operation: WorkbookStructureOperation) => {
      const migrated = migrateWorkspaceStructure(mapping, fieldModel, operation);
      setMapping(migrated.mapping);
      setFieldModel(migrated.model);
      setSchema((current) => writeFieldModel(current, migrated.model));
      setStructureOperations((current) => [...current, operation]);
      markDirty();
    },
    [fieldModel, mapping, markDirty],
  );

  const addField = (groupId?: string) => {
    if (!versionId) return;
    const id = crypto.randomUUID();
    const bindingId = crypto.randomUUID();
    const selectedGroupId = groupId ?? fieldModel.groups[0]?.id ?? 'group-other';
    const dataPath = `/customFields/field_${id.replaceAll('-', '')}`;
    const field: BusinessField = {
      id,
      bindingId,
      dataPath,
      groupId: selectedGroupId,
      name: '新字段',
      kind: 'SCALAR',
      valueType: 'string',
      required: false,
      reviewStatus: 'CONFIRMED',
      fieldCode: templateLocalFieldCode(versionId, { id, name: '新字段', dataPath }),
      fieldOrigin: 'TEMPLATE_LOCAL',
      standardSelectionStatus: 'CUSTOM',
      standardMatchStatus: 'CONFIRMED',
      requiresStandardConfirmation: false,
    };
    const nextModel = { ...fieldModel, fields: [...fieldModel.fields, field] };
    setFieldModel(nextModel);
    setSchema(addCustomFieldSchema(writeFieldModel(schema, nextModel), field));
    setMapping((current) => [
      ...current,
      {
        bindingId,
        fieldCode: field.fieldCode,
        dataPath,
        role: 'FIELD',
        locatorType: workspace?.format === 'DOCX' ? 'DOC_CUSTOM_RANGE' : 'CELL_RANGE',
        locator: {},
        syncDirection: 'TWO_WAY',
        primaryBinding: true,
        bindingStatus: workspace?.format === 'DOCX' ? 'MISSING' : 'VALID',
        diagnostic: { source: 'CUSTOMER_CREATED', displayName: field.name },
      },
    ]);
    setSelectedFieldId(id);
    markDirty();
  };

  const addStructuredField = (
    parent: BusinessField,
    kind: 'REPEAT_FIELD' | 'MATRIX_FIELD',
  ) => {
    if (!versionId || !parent.bindingId) return;
    const parentBinding = mapping.find((binding) => binding.bindingId === parent.bindingId);
    if (!parentBinding) return;
    try {
      const created = createCustomFieldWorkspace(schema, fieldModel, mapping, {
        ownerId: versionId,
        origin: 'TEMPLATE_LOCAL',
        kind,
        name: kind === 'MATRIX_FIELD' ? '新矩阵指标' : '新明细字段',
        parentField: parent,
        parentBinding,
      });
      setSchema(created.schema);
      setFieldModel(created.model);
      setMapping(created.mapping);
      setSelectedFieldId(created.field.id);
      setFieldManagerTab('properties');
      markDirty();
    } catch (reason) {
      void message.error(reason instanceof Error ? reason.message : '字段添加失败');
    }
  };

  const deleteField = (field: BusinessField) => {
    modal.confirm({
      title: `删除字段“${field.name}”？`,
      content: '只会删除字段定义和填写位置，不会删除 Excel 中原有的文字或格式。',
      okText: '删除字段',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => {
        const removed = removeBusinessField(schema, fieldModel, field.id);
        setSchema(removed.schema);
        setFieldModel(removed.model);
        setMapping((current) => current.filter((binding) => !bindingMatchesIdentity(binding, {
          bindingId: field.bindingId,
          relationId: field.relationId,
          fieldId: field.fieldId || field.id,
        })));
        setSelectedFieldId(removed.model.fields[0]?.id);
        markDirty();
      },
    });
  };

  const placeWordField = async (field: BusinessField) => {
    const sourceBinding = mapping.find((binding) => bindingMatchesIdentity(binding, {
      bindingId: field.bindingId,
      relationId: field.relationId,
      fieldId: field.fieldId || field.id,
    })) ?? candidateBinding(field);
    if (!sourceBinding || !field.dataPath) {
      void message.warning('该识别结果缺少可用的正文位置，请先刷新识别结果');
      return;
    }
    try {
      const inserted = await editorRef.current?.insertWordControl?.(
        field.kind === 'SCALAR' ? 'FIELD' : 'REPEAT_REGION',
        `FIELD.${field.id.replaceAll('-', '').slice(0, 12).toUpperCase()}`,
        field.dataPath,
      );
      if (!inserted) throw new Error('请先在 Word 正文中选择插入位置');
      const bindingId = field.bindingId ?? sourceBinding.bindingId;
      const formalBinding: TemplateBinding = {
        ...sourceBinding,
        ...inserted,
        bindingId,
        fieldId: field.fieldId || field.id,
        dataPath: field.dataPath,
        locatorType: 'DOCX_CONTENT_CONTROL',
        syncDirection: 'TWO_WAY',
        primaryBinding: true,
        bindingStatus: 'VALID',
      };
      setMapping((current) => {
        const matched = current.some((binding) => bindingMatchesIdentity(binding, {
          bindingId: field.bindingId,
          relationId: field.relationId,
          fieldId: field.fieldId || field.id,
        }));
        return matched
          ? current.map((binding) => bindingMatchesIdentity(binding, {
              bindingId: field.bindingId,
              relationId: field.relationId,
              fieldId: field.fieldId || field.id,
            }) ? formalBinding : binding)
          : [...current, formalBinding];
      });
      updateField(field.id, {
        bindingId,
        reviewStatus: 'CONFIRMED',
        locator: inserted.locator,
        publishable: true,
      });
      void message.success('Word 正文位置已更新');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '正文位置更新失败');
    }
  };

  const openGroupManager = () => {
    setGroupDrafts(structuredClone(fieldModel.groups));
    setGroupManagerOpen(true);
  };

  const saveGroups = () => {
    const names = groupDrafts.map((group) => normalizeGroupName(group.name));
    if (names.some((name) => !name) || new Set(names).size !== names.length) {
      void message.warning('分组名称不能为空或重复');
      return;
    }
    const nextModel = normalizeFieldModel({
      ...fieldModel,
      groups: groupDrafts.map((group, index) => ({
        ...group,
        name: normalizeGroupName(group.name),
        groupCode: groupCode(group.name),
        order: index,
      })),
    });
    setFieldModel(nextModel);
    setSchema(writeFieldModel(schema, nextModel));
    setGroupManagerOpen(false);
    markDirty();
  };

  const changeView = (next: WorkspaceView) => {
    if (next === 'versions' && editorRef.current) setSnapshot(editorRef.current.getSnapshot());
    setPicking(undefined);
    if (next !== 'edit') setFieldManagerTab('structure');
    if (next === 'edit' && recognitionReview?.recognitionRunId) {
      setFieldManagerTab('recognition');
    }
    setView(next);
  };

  if (loadError) {
    return (
      <Result
        status="error"
        title="工作台加载失败"
        subTitle={loadError}
        extra={<Button onClick={() => navigate('/templates/library')}>返回模板中心</Button>}
      />
    );
  }
  if (!workspace || !snapshot) return <Skeleton active paragraph={{ rows: 12 }} />;

  const editable = workspace.status === 'DRAFT' && isDesktop && view === 'edit';
  const pickingField = picking
    ? fieldModel.fields.find((field) => field.id === picking.fieldId)
    : undefined;

  return (
    <section
      className="workspace-shell template-business-workspace"
      aria-label={`${workspace.name}模板工作台`}
    >
      <header className="workspace-header">
        <div className="workspace-identity">
          <Button
            type="text"
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate('/templates/library')}
          >
            返回
          </Button>
          <span className="workspace-title-block">
            <Typography.Text type="secondary" className="workspace-breadcrumb">
              模板中心 / 模板管理
            </Typography.Text>
            <Typography.Text strong>{workspace.name}</Typography.Text>
          </span>
          <span className="workspace-meta-item">V{workspace.versionNo}</span>
          <span className="workspace-meta-item workspace-format">
            {workspace.format === 'XLSX' ? (
              <>
                <FileExcelOutlined /> Excel
              </>
            ) : (
              <>
                <FileWordOutlined /> Word
              </>
            )}
          </span>
          <Tag color={workspace.status === 'DRAFT' ? 'gold' : 'success'}>
            {workspace.status === 'DRAFT' ? '草稿' : '已发布'}
          </Tag>
        </div>
        <Space wrap>
           <SaveStateIndicator state={saveState} reviewSyncState={reviewSyncState} />
           {reviewSyncState === 'STALE' && (
             <Button
               type="link"
               size="small"
               icon={<ReloadOutlined />}
               onClick={() => void refreshRecognitionReview(true)}
             >
               刷新审核状态
             </Button>
           )}
           {view === 'edit' && workspace.format === 'XLSX' && (
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
           <Dropdown
             menu={{
               items: [
                 ...(versionId ? [{ key: 'current', label: `导出当前${workspace.status === 'DRAFT' ? '草稿' : '版本'}` }] : []),
                 ...(versionHistory.find((item) => item.status === 'PUBLISHED')
                   ? [{ key: 'published', label: '导出已发布版本' }] : []),
               ],
               onClick: ({ key }) => {
                 const published = versionHistory.find((item) => item.status === 'PUBLISHED');
                 const target = key === 'published' && published ? published.versionId : versionId;
                 const state = key === 'published' ? 'PUBLISHED' : workspace.status;
                 if (target && (state === 'DRAFT' || state === 'PUBLISHED')) void exportTemplate(target, state);
               },
             }}
           >
             <Button icon={<DownloadOutlined />}>导出 {workspace.format === 'XLSX' ? 'Excel' : 'Word'} <DownOutlined /></Button>
           </Dropdown>
           <Button
             type="primary"
             className="workspace-publish-button"
             icon={<SendOutlined />}
             disabled={workspace.status !== 'DRAFT' || publishBusy}
             loading={publishBusy}
             onClick={requestPublish}
           >
            发布
          </Button>
        </Space>
      </header>

      <nav className="workspace-view-tabs" aria-label="模板页面">
        <Tabs
          activeKey={view}
          onChange={(key) => changeView(key as WorkspaceView)}
          items={[
            {
              key: 'preview',
              label: (
                <span>
                  <EyeOutlined /> 预览
                </span>
              ),
            },
            { key: 'edit', label: '编辑', disabled: workspace.status !== 'DRAFT' },
            { key: 'versions', label: '版本记录' },
          ]}
        />
      </nav>

      {view === 'versions' ? (
        <VersionView workspace={workspace} saveState={saveState} items={versionHistory} />
      ) : (
        <div className="workspace-main-stage">
          {view === 'edit' && (
            <RecognitionStatusBar
              review={recognitionReview}
              job={recognitionJob}
              busy={recognitionBusy}
              editable={editable}
              allowRestart
              reviewSyncState={reviewSyncState}
              onBegin={beginRecognitionReview}
              onConfirmHigh={confirmHighConfidence}
              onRestart={() => void restartRecognition()}
              onRefreshReview={() => void refreshRecognitionReview(true)}
            />
          )}
          {workspace.format === 'DOCX' && (
            <WordDocumentStatus
              documentStructure={workspace.documentStructure}
              editable={editable}
            />
          )}
          <div className="template-workspace-grid prototype-workspace-grid">
            <main className={`workspace-canvas ${view === 'preview' ? 'is-preview' : ''}`}>
              {picking && pickingField && (
                <div className="selection-guide" role="status">
                  <div>
                    <strong>
                      请在 Excel 中选择“{pickingField.name}”的
                      {picking.target === 'labelAddress' ? '标签单元格' : '填写位置'}
                    </strong>
                    <span>
                      {picking.target === 'labelAddress'
                        ? '请点击一个单元格。'
                        : '可以点击单元格，也可以拖动选择连续区域。'}
                    </span>
                  </div>
                  <Button onClick={() => setPicking(undefined)}>取消选择</Button>
                </div>
              )}
              <Suspense
                fallback={
                  <Spin indicator={<LoadingOutlined spin />} tip="正在加载文档" fullscreen />
                }
              >
                {workspace.format === 'XLSX' ? (
                  <SheetsEditor
                    ref={editorRef}
                    snapshot={snapshot}
                    bindings={mapping}
                    editable={editable}
                    onDirty={handleEditorDirty}
                    onEditorValue={handleEditorValue}
                    onEditorLabel={handleEditorLabel}
                    onSelectionChange={handleSelection}
                    onUnboundCellChange={handleUnboundCellChange}
                    onStructureChange={handleStructureChange}
                  />
                ) : (
                  <div className={editable ? undefined : 'document-readonly'}>
                    <DocsEditor
                      ref={editorRef}
                      snapshot={snapshot ?? {}}
                      editable={editable}
                      onDirty={handleEditorDirty}
                    />
                  </div>
                )}
              </Suspense>
            </main>

            {workspace.format === 'DOCX' ? (
              <DocumentOutlinePanel
                structure={workspace.documentStructure}
                onSelect={(node) => editorRef.current?.focusNode?.(node)}
              />
            ) : (
              <TemplateFieldManager
                editable={editable}
                format={workspace.format}
                fieldModel={fieldModel}
                mapping={mapping}
                selectedFieldId={selectedFieldId}
                selectedRecognitionItemId={selectedRecognitionItemId}
                selectedQualityIssueId={selectedQualityIssueId}
                picking={picking}
                activeTab={fieldManagerTab}
                recognitionReview={recognitionReview}
                recognitionBusy={recognitionBusy}
                onActiveTabChange={setFieldManagerTab}
                onSelectRecognitionItem={focusRecognitionItem}
                onConfirmRecognitionItem={(item, alternativeId) => {
                  void confirmRecognitionItem(item, alternativeId);
                }}
                onModifyRecognitionItem={modifyRecognitionItem}
                onIgnoreRecognitionItem={ignoreRecognitionItem}
                onRestoreRecognitionItem={restoreRecognitionItem}
                onSelectQualityIssue={focusQualityIssue}
                onApplyQualityIssue={(issue) => void applyQualityIssue(issue)}
                onIgnoreQualityIssue={ignoreQualityIssue}
                onRollbackQualityIssue={(issue) => void rollbackQualityIssue(issue)}
                onSelectField={selectField}
                onUpdateField={updateField}
                onUpdateCoordinates={updateCoordinates}
                onPickCoordinate={(fieldId, target) => setPicking({ fieldId, target })}
                onAddField={addField}
                onAddStructuredField={addStructuredField}
                onDeleteField={deleteField}
                onManageGroups={openGroupManager}
                onPlaceWordField={(field) => void placeWordField(field)}
              />
            )}
          </div>
        </div>
      )}

      <Modal
        title="管理业务分组"
        open={groupManagerOpen}
        okText="保存分组"
        cancelText="取消"
        onOk={saveGroups}
        onCancel={() => setGroupManagerOpen(false)}
      >
        <Typography.Paragraph type="secondary">
          分组会同时用于模板字段目录和生产单填写页面。
        </Typography.Paragraph>
        <div className="group-manager-list">
          {groupDrafts.map((group, index) => {
            const fieldCount = fieldModel.fields.filter(
              (field) => field.groupId === group.id,
            ).length;
            return (
              <div key={group.id} className="group-manager-row">
                <Input
                  aria-label={`第 ${index + 1} 个分组名称`}
                  value={group.name}
                  maxLength={40}
                  onChange={(event) =>
                    setGroupDrafts((current) =>
                      current.map((item) =>
                        item.id === group.id ? { ...item, name: event.target.value } : item,
                      ),
                    )
                  }
                />
                <Tag>{fieldCount} 项</Tag>
                <Button
                  type="text"
                  danger
                  aria-label={`删除分组 ${group.name}`}
                  disabled={fieldCount > 0 || groupDrafts.length <= 1}
                  onClick={() =>
                    setGroupDrafts((current) => current.filter((item) => item.id !== group.id))
                  }
                >
                  删除
                </Button>
              </div>
            );
          })}
        </div>
        <Button
          block
          icon={<PlusOutlined />}
          onClick={() =>
            setGroupDrafts((current) => [
              ...current,
              {
                id: `group-${crypto.randomUUID()}`,
                name: `新分组 ${current.length + 1}`,
                order: current.length,
              },
            ])
          }
        >
          新建分组
        </Button>
      </Modal>
    </section>
  );
}

function WordDocumentStatus({
  documentStructure,
  editable,
}: {
  documentStructure?: TemplateWorkspace['documentStructure'];
  editable: boolean;
}) {
  const compatibility = documentStructure?.compatibility;
  const status = compatibility?.status ?? 'DEGRADED';
  const controls = documentStructure?.contentControls?.length ?? 0;
  const blocks = documentStructure?.blocks?.length ?? 0;
  const nodes = documentStructure?.nodeCount ?? documentStructure?.nodes?.length ?? 0;
  const headings = documentStructure?.headingCount ?? 0;
  const type = status === 'BLOCKED' ? 'error' : status === 'DEGRADED' ? 'warning' : 'info';
  const message = status === 'BLOCKED'
    ? '此 Word 文件含有不允许的活动内容，不能作为可发布模板。'
    : status === 'DEGRADED'
      ? '此 Word 文件包含需人工复核的元素；编辑结果请通过差异预览确认。'
      : editable
        ? '当前使用 Word 文档编辑器，可直接编辑正文、段落、表格和常用样式；左侧目录可定位章节。'
        : '当前为只读 Word 文档。';

  return (
    <Alert
      className="word-document-status"
      type={type}
      showIcon
      message={message}
      description={
        <Space size={[8, 4]} wrap>
           <Tag>{controls} 个内容控件</Tag>
           <Tag>{blocks} 个正文块</Tag>
           <Tag>{nodes} 个结构节点</Tag>
           <Tag>{headings} 个章节</Tag>
          {compatibility?.imageCount ? <Tag>{compatibility.imageCount} 张图片待复核</Tag> : null}
          {compatibility?.hasComments ? <Tag>含批注</Tag> : null}
          {compatibility?.hasFootnotes || compatibility?.hasEndnotes ? <Tag>含脚注/尾注</Tag> : null}
        </Space>
      }
    />
  );
}

function VersionView({
  workspace,
  saveState,
  items,
}: {
  workspace: TemplateWorkspace;
  saveState: SaveState;
  items: TemplateVersionHistoryItem[];
}) {
  return (
    <main className="version-history-page">
      <div className="version-history-heading">
        <Typography.Title level={4}>版本记录</Typography.Title>
        <Typography.Text type="secondary">
          发布后版本会锁定，生产单始终保留创建时使用的模板版本。
        </Typography.Text>
      </div>
      <div className="version-history-list">
        {(items.length
          ? items
          : [
              {
                versionId: workspace.versionId,
                versionNo: workspace.versionNo,
                status: workspace.status,
                createdAt: new Date().toISOString(),
                updatedAt: new Date().toISOString(),
                saveCount: 0,
              },
            ]
        ).map((item) => (
          <article className="version-history-item" key={item.versionId}>
            <div className="version-marker">V{item.versionNo}</div>
            <div>
              <Space wrap>
                <Typography.Text strong>
                  {item.versionId === workspace.versionId ? '当前版本' : `版本 V${item.versionNo}`}
                </Typography.Text>
                <Tag color={item.status === 'DRAFT' ? 'gold' : 'green'}>
                  {item.status === 'DRAFT'
                    ? '草稿'
                    : item.status === 'PUBLISHED'
                      ? '已发布'
                      : '已停用'}
                </Tag>
              </Space>
              <Typography.Paragraph type="secondary">
                最近更新：{new Date(item.updatedAt).toLocaleString('zh-CN')} · 已保存{' '}
                {item.saveCount} 次
                {item.versionId === workspace.versionId && saveState !== 'SAVED'
                  ? ' · 当前还有尚未保存的修改'
                  : ''}
              </Typography.Paragraph>
            </div>
          </article>
        ))}
      </div>
    </main>
  );
}

function RecognitionStatusBar({
  review,
  job,
  busy,
  editable,
  allowRestart,
  reviewSyncState,
  onBegin,
  onConfirmHigh,
  onRestart,
  onRefreshReview,
}: {
  review?: RecognitionReview;
  job?: TemplateImportJob;
  busy: boolean;
  editable: boolean;
  allowRestart: boolean;
  reviewSyncState: ReviewSyncState;
  onBegin: () => void;
  onConfirmHigh: () => void;
  onRestart: () => void;
  onRefreshReview: () => void;
}) {
  const summary = review?.summary;
  const recognitionFailed = review?.runStatus === 'FAILED';
  const tone = recognitionFailed
    ? 'pending'
    : (summary?.conflict ?? 0) > 0 || (summary?.blockingIssueCount ?? 0) > 0
      ? 'conflict'
      : (summary?.pending ?? 0) > 0
        ? 'pending'
        : review?.recognitionRunId
          ? 'complete'
          : 'empty';
  return (
    <div className="recognition-status-bar" data-tone={tone} role="status">
      <div className="recognition-status-content">
        <span className="recognition-status-icon">
          {tone === 'conflict' ? (
            <ExclamationCircleOutlined />
          ) : tone === 'complete' ? (
            <CheckCircleOutlined />
          ) : (
            <RobotOutlined />
          )}
        </span>
        <strong>
          {busy
            ? '正在重新识别模板'
            : recognitionFailed
              ? '智能识别未完成'
              : tone === 'conflict'
                ? '识别结果存在冲突'
                : tone === 'complete'
                  ? 'AI 识别已完成'
                  : review?.recognitionRunId
                    ? 'AI 识别待确认'
                    : '尚未生成识别结果'}
        </strong>
        {busy && job ? (
          <span>
            {recognitionStageLabel(job.currentStage)} · {job.progress}%
          </span>
        ) : recognitionFailed ? (
          <span>工作簿和原有字段已保留，可重新识别</span>
        ) : tone === 'complete' ? (
          <span>
            区域 {review?.statistics?.regionCount ?? 0} ｜ 字段 {review?.statistics?.fieldCount ?? 0} ｜
            运行时槽位 {review?.statistics?.runtimeSlotCount ?? 0}
          </span>
        ) : (
          <span>
            区域 {review?.statistics?.regionCount ?? 0} ｜ 字段 {review?.statistics?.fieldCount ?? 0} ｜
            待确认字段 {review?.statistics?.pendingFieldCount ?? 0} ｜ 结构冲突{' '}
            {review?.statistics?.structureConflictGroups ?? 0} ｜ 运行时槽位{' '}
            {review?.statistics?.runtimeSlotCount ?? 0}
          </span>
        )}
        {reviewSyncState === 'STALE' && <span className="recognition-sync-stale">审核状态需刷新</span>}
      </div>
      {busy && job && (
        <Progress className="recognition-inline-progress" percent={job.progress} showInfo={false} />
      )}
      <Space size={6}>
        {reviewSyncState !== 'FRESH' && (
          <Button
            size="small"
            icon={<ReloadOutlined />}
            loading={reviewSyncState === 'REFRESHING'}
            onClick={onRefreshReview}
          >
            刷新审核状态
          </Button>
        )}
        <Button size="small" onClick={onBegin} disabled={busy || !review?.recognitionRunId}>
          开始确认
        </Button>
        <Button
          size="small"
          icon={<ThunderboltOutlined />}
          onClick={onConfirmHigh}
          disabled={
            busy ||
            !editable ||
            !review?.items.some((item) => item.status === 'PENDING' && item.confidence >= 0.85)
          }
        >
          一键确认明确项目
        </Button>
        {allowRestart && (
          <Button
            size="small"
            icon={<ReloadOutlined />}
            loading={busy}
            disabled={!editable}
            onClick={onRestart}
          >
            重新识别
          </Button>
        )}
      </Space>
    </div>
  );
}

function updateRecognitionReview(
  review: RecognitionReview,
  updates: Map<string, RecognitionReviewItem['status']>,
): RecognitionReview {
  const items = review.items.map((item) =>
    updates.has(item.id) ? { ...item, status: updates.get(item.id) ?? item.status } : item,
  );
  const active = items.filter((item) => item.status !== 'IGNORED');
  return {
    ...review,
    items,
    groups: [...new Set(active.map((item) => item.groupName))],
    summary: {
      total: active.length,
      confirmed: active.filter((item) => item.status === 'CONFIRMED').length,
      pending: active.filter((item) => item.status === 'PENDING').length,
      lowConfidence: active.filter((item) => item.confidence < 0.65).length,
      conflict: active.filter((item) => item.status === 'CONFLICT').length,
      ignored: items.filter((item) => item.status === 'IGNORED').length,
      scalar: active.filter((item) => item.kind === 'SCALAR').length,
      rowTable: active.filter((item) => item.kind === 'ROW_TABLE').length,
      matrix: active.filter((item) => item.kind === 'MATRIX').length,
      qualityIssueCount: review.qualityIssues.length,
      autoFixedCount: review.qualityIssues.filter((item) => item.status === 'AUTO_APPLIED').length,
      blockingIssueCount: review.qualityIssues.filter(
        (item) =>
          item.severity === 'BLOCKER' &&
          !['AUTO_APPLIED', 'CONFIRMED', 'IGNORED'].includes(item.status),
      ).length,
    },
  };
}

function editorLabelText(value: unknown) {
  if (value === null || value === undefined) return '';
  return typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'
    ? String(value)
    : '';
}

function nextRecognitionItem(review?: RecognitionReview, excludedId?: string) {
  if (!review) return undefined;
  const active = review.items.filter((item) => item.id !== excludedId && item.status !== 'IGNORED');
  return (
    active.find((item) => item.status === 'CONFLICT') ??
    active.find((item) => item.status === 'PENDING' && item.confidence < 0.65) ??
    active.find((item) => item.status === 'PENDING')
  );
}

function recognitionStageLabel(stage?: string) {
  const labels: Record<string, string> = {
    LOADING_FILE: '读取工作簿',
    READING_STRUCTURE: '分析表格结构',
    RECOGNIZING_FIELDS: '识别业务字段',
    RECOGNIZING_COMPLEX_REGIONS: '识别复杂区域',
    RECOGNIZING_WORKBOOK_SEMANTICS: '理解整份工作簿',
    BUILDING_DRAFT: '生成识别结果',
    PERSISTING_RESULT: '保存识别结果',
  };
  return labels[stage ?? ''] ?? '正在处理';
}

function delay(milliseconds: number) {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

function snapshotSignature(snapshot: Record<string, unknown>) {
  return JSON.stringify(snapshot);
}

function SaveStateIndicator({
  state,
  reviewSyncState,
}: {
  state: SaveState;
  reviewSyncState: ReviewSyncState;
}) {
  const config = {
    SAVED: {
      icon: <CheckCircleOutlined />,
      text: reviewSyncState === 'STALE' ? '已保存，审核需刷新' : '已保存，可发布',
      color: reviewSyncState === 'STALE' ? 'var(--app-warning)' : 'var(--app-success)',
    },
    DIRTY: { icon: <CloudUploadOutlined />, text: '未保存', color: 'var(--app-warning)' },
    SAVING: { icon: <LoadingOutlined spin />, text: '保存中', color: 'var(--app-primary)' },
    FAILED: { icon: <ExclamationCircleOutlined />, text: '保存失败', color: 'var(--app-danger)' },
    CONFLICT: {
      icon: <ExclamationCircleOutlined />,
      text: '内容有更新',
      color: 'var(--app-danger)',
    },
  }[state];
  return (
    <span className="save-state" style={{ color: config.color }} aria-live="polite">
      {config.icon}
      {config.text}
    </span>
  );
}

function fieldsMissingRequiredPosition(
  model: FieldModel,
  mapping: TemplateBinding[],
  format: TemplateWorkspace['format'],
) {
  return model.fields.filter((field) => {
    if (field.candidate) return false;
    if (!field.required) return false;
    const binding = mapping.find((item) => bindingMatchesIdentity(item, {
      bindingId: field.bindingId,
      relationId: field.relationId,
      fieldId: field.fieldId || field.id,
    }));
    if (!binding) return true;
    if (format === 'DOCX') return !binding.markerId;
    const address = stringValue(binding.locator.address) || stringValue(binding.locator.range);
    return !address || Boolean(validateAddress(address, false));
  });
}

function nonBlockingWarnings(
  model: FieldModel,
  mapping: TemplateBinding[],
  format: TemplateWorkspace['format'],
) {
  const warnings: string[] = [];
  const lowConfidence = model.fields.filter(
    (field) => field.reviewStatus === 'NEEDS_CONFIRMATION' || (field.confidence ?? 1) < 0.85,
  ).length;
  const optionalWithoutPosition = model.fields.filter((field) => {
    if (field.candidate) return false;
    if (field.required) return false;
    const binding = mapping.find((item) => bindingMatchesIdentity(item, {
      bindingId: field.bindingId,
      relationId: field.relationId,
      fieldId: field.fieldId || field.id,
    }));
    if (!binding) return true;
    return format === 'DOCX'
      ? !binding.markerId
      : !stringValue(binding.locator.address || binding.locator.range);
  }).length;
  const duplicated = duplicatePositionCount(mapping);
  if (lowConfidence) warnings.push(`${lowConfidence} 个字段建议核对`);
  if (optionalWithoutPosition) warnings.push(`${optionalWithoutPosition} 个可选字段没有填写位置`);
  if (duplicated) warnings.push(`${duplicated} 处填写位置重复`);
  return warnings;
}

function duplicatePositionCount(mapping: TemplateBinding[]) {
  const positions = new Set<string>();
  let count = 0;
  for (const binding of mapping) {
    const address = stringValue(binding.locator.address || binding.locator.range);
    if (!address) continue;
    const key = `${stringValue(binding.locator.sheetId) || stringValue(binding.locator.sheetName)}:${address}`;
    if (positions.has(key)) count += 1;
    positions.add(key);
  }
  return count;
}

function addCustomFieldSchema(schema: Record<string, unknown>, field: BusinessField) {
  const next = structuredClone(schema);
  const properties = isRecord(next.properties) ? next.properties : {};
  const customFields = isRecord(properties.customFields)
    ? properties.customFields
    : { type: 'object', properties: {} };
  const customProperties = isRecord(customFields.properties) ? customFields.properties : {};
  const key = field.dataPath?.split('/').at(-1) ?? field.id;
  customProperties[key] = { type: 'string', title: field.name };
  customFields.properties = customProperties;
  properties.customFields = customFields;
  next.properties = properties;
  return next;
}

function reflowStructuredLocator(
  locator: Record<string, unknown>,
  kind: BusinessField['kind'],
  previous?: Record<string, unknown>,
) {
  if (kind !== 'MATRIX') return locator;
  const overall = parseA1Range(stringValue(locator.address));
  if (!overall) return locator;
  const previousOverall = parseA1Range(stringValue(previous?.address));
  const previousData = parseA1Range(stringValue(previous?.dataRange));
  const headerRows =
    previousOverall && previousData
      ? Math.max(1, previousData.startRow - previousOverall.startRow)
      : 1;
  const rowHeaderColumns =
    previousOverall && previousData
      ? Math.max(1, previousData.startColumn - previousOverall.startColumn)
      : 1;
  const dataStartRow = Math.min(overall.endRow, overall.startRow + headerRows);
  const dataStartColumn = Math.min(overall.endColumn, overall.startColumn + rowHeaderColumns);
  return {
    ...locator,
    rowHeaderRange: formatA1Range({
      startRow: dataStartRow,
      endRow: overall.endRow,
      startColumn: overall.startColumn,
      endColumn: Math.max(overall.startColumn, dataStartColumn - 1),
    }),
    columnHeaderRange: formatA1Range({
      startRow: overall.startRow,
      endRow: Math.max(overall.startRow, dataStartRow - 1),
      startColumn: dataStartColumn,
      endColumn: overall.endColumn,
    }),
    dataRange: formatA1Range({
      startRow: dataStartRow,
      endRow: overall.endRow,
      startColumn: dataStartColumn,
      endColumn: overall.endColumn,
    }),
  };
}

function matrixModelFromLocator(locator: Record<string, unknown>): MatrixModel {
  const rowHeaderRange = stringValue(locator.rowHeaderRange);
  const columnHeaderRange = stringValue(locator.columnHeaderRange);
  const crossDataRange = stringValue(locator.crossDataRange);
  return {
    semanticMode: stringValue(locator.semanticMode) === 'RECORD_SET'
      ? 'RECORD_SET'
      : stringValue(locator.semanticMode) === 'CROSS_TAB' ? 'CROSS_TAB' : 'UNKNOWN',
    layoutMode: stringValue(locator.layoutMode) === 'LONG_FORM'
      ? 'LONG_FORM' : stringValue(locator.layoutMode) === 'CROSS_TAB' ? 'CROSS_TAB' : 'UNKNOWN',
    recordAxis: ['ROW', 'COLUMN'].includes(stringValue(locator.recordAxis))
      ? stringValue(locator.recordAxis) as MatrixModel['recordAxis'] : 'UNKNOWN',
    rowHeaderRange,
    columnHeaderRange,
    crossDataRange,
    headerRange: stringValue(locator.headerRange),
    dataRange: stringValue(locator.dataRange),
    cornerRange: stringValue(locator.cornerRange) || undefined,
    columnMemberRole: 'COLUMN_MEMBER_INPUT',
    memberMode: 'RUNTIME_INPUT',
  };
}

interface A1Range {
  startRow: number;
  endRow: number;
  startColumn: number;
  endColumn: number;
}

function parseA1Range(value: string): A1Range | undefined {
  const match = /^([A-Z]{1,4})([1-9][0-9]*)(?::([A-Z]{1,4})([1-9][0-9]*))?$/i.exec(value);
  if (!match) return undefined;
  const startColumn = columnNumber(match[1] ?? '');
  const startRow = Number(match[2]);
  const endColumn = columnNumber(match[3] ?? match[1] ?? '');
  const endRow = Number(match[4] ?? match[2]);
  return {
    startRow: Math.min(startRow, endRow),
    endRow: Math.max(startRow, endRow),
    startColumn: Math.min(startColumn, endColumn),
    endColumn: Math.max(startColumn, endColumn),
  };
}

function formatA1Range(range: A1Range) {
  const start = `${columnLetters(range.startColumn)}${range.startRow}`;
  const end = `${columnLetters(range.endColumn)}${range.endRow}`;
  return start === end ? start : `${start}:${end}`;
}

function findFormRegionForCell(
  review: RecognitionReview | undefined,
  sheetId: string,
  address: string,
) {
  const cell = parseA1Range(address);
  if (!cell) return undefined;
  return review?.regions?.find((region) => {
    if (region.kind !== 'FORM_REGION' || region.sheetId && region.sheetId !== sheetId) return false;
    const range = parseA1Range(region.range || '');
    return Boolean(
      range
      && cell.startRow >= range.startRow
      && cell.startRow <= range.endRow
      && cell.startColumn >= range.startColumn
      && cell.startColumn <= range.endColumn,
    );
  });
}

function columnNumber(value: string) {
  return [...value.toUpperCase()].reduce(
    (result, letter) => result * 26 + letter.charCodeAt(0) - 64,
    0,
  );
}

function columnLetters(column: number) {
  let value = column;
  let result = '';
  while (value > 0) {
    value -= 1;
    result = String.fromCharCode(65 + (value % 26)) + result;
    value = Math.floor(value / 26);
  }
  return result;
}

function stringValue(value: unknown) {
  return typeof value === 'string' ? value : '';
}

function emptyWordFieldModel(): FieldModel {
  return {
    modelVersion: 4,
    groups: [],
    fields: [],
    blocks: [],
    semanticAnnotations: [],
  };
}

function normalizeWordSchema(schema: Record<string, unknown>) {
  const normalized = { ...schema };
  delete normalized['x-jsd-field-model'];
  return {
    ...normalized,
    type: 'object',
    documentType: 'WORD',
    schemaVersion: 1,
  };
}

function lastValue(value: unknown) {
  const values = unknownArray(value);
  if (!values) return value;
  return values.length ? values[values.length - 1] : undefined;
}

function hasMeaningfulTail(value: unknown, binding?: TemplateBinding) {
  const values = unknownArray(value);
  if (values && binding) {
    const size = Math.max(
      1,
      binding.repeatAxis === 'COLUMN' ? binding.recordWidth || 1 : binding.recordHeight || 1,
    );
    const stride = Math.max(size, binding.recordStride || 1);
    const start = Math.max(0, values.length - stride);
    const tail = values.slice(start, start + size);
    return tail.some((item) =>
      Array.isArray(item)
        ? item.some((nested) => hasMeaningfulValue(nested))
        : hasMeaningfulValue(item),
    );
  }
  const tail = lastValue(value);
  const tailValues = unknownArray(tail);
  if (tailValues) return tailValues.some((item) => hasMeaningfulValue(item));
  return hasMeaningfulValue(tail);
}

function hasMeaningfulValue(value: unknown) {
  if (value === undefined || value === null) return false;
  if (typeof value === 'string') return value.trim() !== '';
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
    return String(value).trim() !== '';
  }
  return false;
}

function snapshotVersion(snapshot: Record<string, unknown>, fallback: number) {
  const value = snapshot.snapshotFormatVersion;
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : fallback;
}

function requiresServerStructureConfirmation(item: RecognitionReviewItem) {
  return (
    ['FORM_REGION', 'ROW_TABLE', 'COLUMN_TABLE', 'MATRIX', 'TABLE_REGION'].includes(item.kind) &&
    (Boolean(item.payload.candidateOnly) ||
      Boolean(item.payload.physicalStructureOnly) ||
      Boolean(item.payload.structureConflict) ||
      item.payload.canonicalStatus !== 'CONFIRMED' ||
      item.payload.structureStatus !== 'CONFIRMED')
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function unknownArray(value: unknown): unknown[] | undefined {
  return Array.isArray(value) ? (value as unknown[]) : undefined;
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
