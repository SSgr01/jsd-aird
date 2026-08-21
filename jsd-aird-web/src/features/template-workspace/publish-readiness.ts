export type PublishSaveState = 'SAVED' | 'DIRTY' | 'SAVING' | 'FAILED' | 'CONFLICT';
export type PublishReviewSyncState = 'FRESH' | 'REFRESHING' | 'STALE';
export type PublishReviewStatus = 'NOT_SUBMITTED' | 'SUBMITTED' | 'APPROVED' | 'REJECTED';

import type { BusinessField, FieldModel, TemplateBinding, TemplateFormat } from './types';
import { hasLocatorLabel, locatorLabelRange, mergeLocators } from './locator';

export function fieldsMissingLabelPosition(
  model: FieldModel,
  mapping: TemplateBinding[],
  format: TemplateFormat,
): BusinessField[] {
  if (format !== 'XLSX') return [];
  return model.fields.filter((field) => {
    if (field.fieldType === 'REGION' || field.fieldType === 'MANUAL_VALUE') return false;
    const binding = mapping.find((item) => item.bindingId === field.bindingId);
    const locator = mergeLocators(field.locator, binding?.locator);
    return !hasLocatorLabel(locator) && !locatorLabelRange(field.locator);
  });
}

export function publishReadinessBlocker(
  saveState: PublishSaveState,
  reviewSyncState: PublishReviewSyncState,
  reviewStatus: PublishReviewStatus = 'APPROVED',
): string | undefined {
  if (saveState !== 'SAVED') return '请先保存当前修改，再发布模板';
  if (reviewSyncState === 'REFRESHING') return '正在刷新识别审核状态，请稍候';
  if (reviewSyncState === 'STALE') return '审核状态需要刷新后才能发布';
  if (reviewStatus === 'NOT_SUBMITTED') return '请先提交模板审核，通过后才能发布';
  if (reviewStatus === 'SUBMITTED') return '模板正在审核中，通过后才能发布';
  if (reviewStatus === 'REJECTED') return '模板审核未通过，请修改后重新提交审核';
  return undefined;
}
