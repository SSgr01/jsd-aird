import { App } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { readFieldModel } from '@/features/template-workspace/field-model';
import { mergeRecognitionReview } from '@/features/template-workspace/recognition-review';
import {
  TemplateFieldManager,
  type FieldManagerTab,
} from '@/pages/template-workspace/TemplateFieldManager';
import { productionUploadApi } from '@/services/production-orders/production-upload-api';

type ManagerProps = React.ComponentProps<typeof TemplateFieldManager>;
type RecognitionItem = NonNullable<ManagerProps['recognitionReview']>['items'][number];

/** The experiment tab only exists for experiment templates, which uploads never are. */
const EMPTY_EXPERIMENT_CONFIG = {} as ManagerProps['experimentConfiguration'];

/**
 * Field review for a recognized production order, rendered by the template
 * center's own field manager so both screens share one layout and one interaction:
 * 识别确认 / 字段结构 / 字段属性.
 *
 * <p>Recognition stores the review in the template center's contract together with
 * the template's field model, so this adapter only overlays the persisted review
 * status from the field table and forwards decisions back to the upload.</p>
 */
export function ProductionUploadFieldManagerPanel({
  uploadId,
  format,
  recognitionResult,
  canUpdate,
  activeTab,
  onActiveTabChange,
  refreshToken = 0,
  onSaved,
  onFocusItem,
}: {
  uploadId: string;
  format: 'XLSX' | 'DOCX';
  recognitionResult?: Record<string, unknown>;
  canUpdate: boolean;
  activeTab: FieldManagerTab;
  onActiveTabChange: (tab: FieldManagerTab) => void;
  /** Bumped by the page after a bulk confirm so the panel re-reads field statuses. */
  refreshToken?: number;
  onSaved?: () => void;
  onFocusItem?: (item: RecognitionItem) => void;
}) {
  const { message } = App.useApp();
  const [fieldStatus, setFieldStatus] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [selectedFieldId, setSelectedFieldId] = useState<string>();
  const [selectedRecognitionItemId, setSelectedRecognitionItemId] = useState<string>();

  const mapping = (recognitionResult?.mapping ?? []) as ManagerProps['mapping'];
  const storedReview = recognitionResult?.recognitionReview as ManagerProps['recognitionReview'];
  const reviewReady = Boolean(storedReview?.recognitionRunId);

  // The field table is the persisted outcome of the review, so it decides which
  // items are already confirmed when the workspace is opened again.
  const loadFieldStatus = useCallback(async () => {
    try {
      const fields = await productionUploadApi.fields(uploadId);
      setFieldStatus(
        Object.fromEntries(
          fields
            .filter((field) => field.reviewStatus)
            .map((field) => [
              `${field.fieldCode ?? ''}|${field.dataPath ?? ''}|${field.recordIndex ?? ''}`,
              field.reviewStatus,
            ]),
        ),
      );
    } catch {
      // The overlay is a refinement; a failure leaves the stored statuses in place.
    }
  }, [uploadId]);

  useEffect(() => {
    void loadFieldStatus();
  }, [loadFieldStatus, refreshToken]);

  const review = useMemo(() => {
    if (!storedReview) return undefined;
    return {
      ...storedReview,
      items: (storedReview.items ?? []).map((item) => {
        const recordIndex = (item.payload as { recordIndex?: number } | undefined)?.recordIndex;
        const key = `${item.payload?.fieldCode ?? ''}|${item.payload?.dataPath ?? ''}|${recordIndex ?? ''}`;
        const status = item.status === 'CONFIRMED' || item.status === 'IGNORED'
          ? item.status : normalizeReviewStatus(fieldStatus[key]);
        return status ? { ...item, status } : item;
      }),
    };
  }, [fieldStatus, storedReview]);

  const fieldModel = useMemo<ManagerProps['fieldModel']>(() => {
    const stored = recognitionResult?.fieldModel;
    if (isFieldModel(stored)) return stored;
    if (mapping.length > 0) return readFieldModel({}, mapping);
    // A free Excel upload carries no template, so it has neither a field model nor
    // a mapping to read one from — its recognized fields are the review's own
    // candidates. Deriving them the way the template center does is what keeps
    // 字段结构 and 识别确认 describing the same fields; reading the empty mapping
    // left the structure empty while the review listed every field.
    if (!review) return readFieldModel({}, mapping);
    return mergeRecognitionReview({}, [], readFieldModel({}, []), review).model;
  }, [mapping, review, recognitionResult?.fieldModel]);

  const decideItem = async (item: RecognitionItem, reviewStatus: 'CONFIRMED' | 'IGNORED' | 'PENDING') => {
    const fieldCode = item.payload?.fieldCode;
    const dataPath = item.payload?.dataPath;
    const recordIndex = (item.payload as { recordIndex?: number } | undefined)?.recordIndex;
    setBusy(true);
    try {
      await productionUploadApi.confirmFields(uploadId, [
        { fieldCode, dataPath, recordIndex, reviewStatus },
      ]);
      setFieldStatus((current) => ({
        ...current,
        [`${fieldCode ?? ''}|${dataPath ?? ''}|${recordIndex ?? ''}`]: reviewStatus,
      }));
      void message.success(`已${reviewStatus === 'CONFIRMED' ? '确认' : reviewStatus === 'IGNORED' ? '忽略' : '恢复'}“${item.fieldName}”`);
      onSaved?.();
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '字段确认失败');
    } finally {
      setBusy(false);
    }
  };

  const selectFieldForItem = (item: RecognitionItem) => {
    setSelectedRecognitionItemId(item.id);
    onFocusItem?.(item);
    const field = fieldModel.fields.find((candidate) =>
      (item.payload?.fieldId && (candidate.fieldId === item.payload.fieldId || candidate.id === item.payload.fieldId))
      || (item.payload?.bindingId && candidate.bindingId === item.payload.bindingId)
      || (item.payload?.relationId && candidate.relationId === item.payload.relationId)
      || (item.payload?.dataPath && candidate.dataPath === item.payload.dataPath),
    );
    if (field) setSelectedFieldId(field.id);
  };

  return (
    <TemplateFieldManager
      editable={canUpdate && reviewReady}
      fieldDefinitionEditable={false}
      format={format}
      fieldModel={fieldModel}
      mapping={mapping}
      selectedFieldId={selectedFieldId}
      selectedRecognitionItemId={selectedRecognitionItemId}
      activeTab={activeTab}
      recognitionReview={review}
      recognitionBusy={busy}
      experimentConfiguration={EMPTY_EXPERIMENT_CONFIG}
      onExperimentConfigurationChange={() => undefined}
      onActiveTabChange={onActiveTabChange}
      onSelectRecognitionItem={selectFieldForItem}
      onConfirmRecognitionItem={(item) => void decideItem(item, 'CONFIRMED')}
      onModifyRecognitionItem={(item) => {
        selectFieldForItem(item);
        onActiveTabChange('properties');
      }}
      onIgnoreRecognitionItem={(item) => void decideItem(item, 'IGNORED')}
      onRestoreRecognitionItem={(item) => void decideItem(item, 'PENDING')}
      onSelectQualityIssue={() => undefined}
      onApplyQualityIssue={() => undefined}
      onIgnoreQualityIssue={() => undefined}
      onRollbackQualityIssue={() => undefined}
      onSelectField={(field) => {
        setSelectedFieldId(field.id);
        setSelectedRecognitionItemId(field.recognitionItemId);
        const item = review?.items.find((candidate) => candidate.id === field.recognitionItemId
          || candidate.payload?.dataPath === field.dataPath);
        if (item) onFocusItem?.(item);
      }}
      onUpdateField={() => undefined}
      onUpdateCoordinates={() => undefined}
      onPickCoordinate={() => undefined}
      onPickProjectionRange={() => undefined}
      onAddField={() => undefined}
      onAddStructuredField={() => undefined}
      onDeleteField={() => undefined}
      onManageGroups={() => undefined}
      onPlaceWordField={() => undefined}
    />
  );
}

function normalizeReviewStatus(value?: string): RecognitionItem['status'] | undefined {
  switch (value) {
    case 'CONFIRMED':
      return 'CONFIRMED';
    case 'IGNORED':
      return 'IGNORED';
    case 'CONFLICT':
      return 'CONFLICT';
    case 'PENDING':
    case 'EXTRACTED':
    case 'NEEDS_REVIEW':
      return 'PENDING';
    default:
      return undefined;
  }
}

function isFieldModel(value: unknown): value is ManagerProps['fieldModel'] {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const model = value as Record<string, unknown>;
  return Array.isArray(model.groups) && Array.isArray(model.fields);
}
