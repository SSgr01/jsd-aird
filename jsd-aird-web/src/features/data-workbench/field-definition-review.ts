import type { DataIssue, DataSheet } from '@/services/data/data-api';
import type {
  InstanceIssue,
  WorkbookTemplateInstance,
} from '@/features/template-workspace/workbook-instance';
import type { EditorSelection } from '@/features/template-workspace/types';

export interface FieldDefinitionReviewItem {
  fieldId: string;
  bindingId?: string;
  name: string;
  sheetName?: string;
  address?: string;
  valueType: string;
  unit?: string;
}

type DefinitionIssue = InstanceIssue | DataIssue;

function issueCode(issue: DefinitionIssue) {
  if ('code' in issue) return issue.code;
  return issue.issueType === 'FIELD_UNCONFIRMED'
    ? 'FIELD_UNCONFIRMED'
    : issue.detail?.code === 'FIELD_UNCONFIRMED'
      ? 'FIELD_UNCONFIRMED'
      : issue.issueType;
}

function issueFieldId(issue: DefinitionIssue) {
  if ('fieldId' in issue && issue.fieldId) return issue.fieldId;
  return 'detail' in issue && typeof issue.detail?.fieldId === 'string'
    ? issue.detail.fieldId
    : undefined;
}

export function normalizedCellAddress(value?: string) {
  return String(value || '').replaceAll('$', '').split(':')[0]!.toUpperCase();
}

/** Build the same short review list in import jobs and saved experiment records. */
export function definitionReviewItems(
  instance: WorkbookTemplateInstance | undefined,
  issues: readonly DefinitionIssue[],
): FieldDefinitionReviewItem[] {
  if (!instance) return [];
  return instance.model.fields.flatMap((field) => {
    if (field.displayRole === 'REGION') return [];
    const binding = instance.mapping.find((item) => item.bindingId === field.bindingId);
    const locator = binding?.locator ?? field.locator ?? {};
    const address = String(
      locator.labelRange || field.labelRange || field.locator?.labelRange
      || locator.address || locator.valueRange || field.valueRange || '',
    );
    const sheetId = String(locator.sheetId || field.locator?.sheetId || '');
    const definitionConfirmed = field.reviewStatus === 'CONFIRMED'
      && field.experimentSemanticStatus !== 'NEEDS_REVIEW';
    if (definitionConfirmed) return [];
    const pendingNewField = field.fieldOrigin === 'INSTANCE_LOCAL';
    const hasReportedIssue = issues.some((issue) => {
      if (issueCode(issue) !== 'FIELD_UNCONFIRMED') return false;
      if (issueFieldId(issue) === field.id) return true;
      const sameAddress = normalizedCellAddress(issue.address)
        && normalizedCellAddress(issue.address) === normalizedCellAddress(address);
      if (sameAddress && (!issue.sheetId || !sheetId || String(issue.sheetId) === sheetId)) return true;
      return issue.message.includes(`字段“${field.name}”`)
        || issue.message.includes(`项目“${field.name}”`);
    });
    if (!pendingNewField && !hasReportedIssue) return [];
    return [{
      fieldId: field.id,
      bindingId: field.bindingId,
      name: field.name,
      sheetName: String(locator.sheetName || field.locator?.sheetName || ''),
      address,
      valueType: field.valueType,
      unit: field.unit,
    }];
  });
}

export function issueRepresentedByDefinition(
  issue: DefinitionIssue,
  reviews: readonly FieldDefinitionReviewItem[],
) {
  if (issueCode(issue) !== 'FIELD_UNCONFIRMED') return false;
  return reviews.some((item) => issueFieldId(issue) === item.fieldId
    || Boolean(normalizedCellAddress(issue.address)
      && normalizedCellAddress(issue.address) === normalizedCellAddress(item.address))
    || issue.message.includes(`字段“${item.name}”`)
    || issue.message.includes(`项目“${item.name}”`));
}

export function fieldDefinitionIssueLocation(
  issue: DataIssue,
  instance: WorkbookTemplateInstance,
  sheets: DataSheet[],
): EditorSelection | undefined {
  let sheetId = issue.sheetId;
  let address = issue.address;
  const fieldId = issueFieldId(issue);
  const field = fieldId ? instance.model.fields.find((item) => item.id === fieldId) : undefined;
  const binding = field ? instance.mapping.find((item) => item.bindingId === field.bindingId) : undefined;
  const locator = (binding?.locator || field?.locator || {}) as Record<string, unknown>;
  const locatorText = (key: string) => typeof locator[key] === 'string' ? locator[key] as string : undefined;
  sheetId ||= locatorText('sheetId');
  address ||= locatorText('labelRange') || locatorText('labelAddress');
  if (!sheetId || !address) return undefined;
  const sheetName = sheets.find((sheet) => sheet.sheetId === sheetId)?.sheetName
    || locatorText('sheetName') || '当前工作表';
  return { sheetId, sheetName, address };
}

export function deduplicateFieldDefinitionIssues(
  issues: DataIssue[],
  instance: WorkbookTemplateInstance | undefined,
  sheets: DataSheet[],
) {
  const result: DataIssue[] = [];
  const indexByKey = new Map<string, number>();
  for (const issue of issues) {
    if (issueCode(issue) !== 'FIELD_UNCONFIRMED') {
      result.push(issue);
      continue;
    }
    const field = instance ? fieldForDefinitionIssue(issue, instance) : undefined;
    if (field?.reviewStatus === 'CONFIRMED' && field.experimentSemanticStatus !== 'NEEDS_REVIEW') continue;
    const location = instance ? fieldDefinitionIssueLocation(issue, instance, sheets) : undefined;
    const fieldId = issueFieldId(issue) || '';
    const key = location
      ? `${location.sheetId}:${normalizedCellAddress(location.address)}`
      : `field:${fieldId || issue.message}`;
    const existing = indexByKey.get(key);
    if (existing === undefined) {
      indexByKey.set(key, result.length);
      result.push(issue);
    } else if (!result[existing]?.address && issue.address) result[existing] = issue;
  }
  return result;
}

function fieldForDefinitionIssue(issue: DataIssue, instance: WorkbookTemplateInstance) {
  const fieldId = issueFieldId(issue);
  if (fieldId) return instance.model.fields.find((field) => field.id === fieldId);
  if (!issue.address) return undefined;
  const address = normalizedCellAddress(issue.address);
  const binding = instance.mapping.find((item) => {
    const locator = item.locator as Record<string, unknown>;
    const label = typeof locator.labelRange === 'string' ? locator.labelRange
      : typeof locator.labelAddress === 'string' ? locator.labelAddress : '';
    const sheetId = typeof locator.sheetId === 'string' ? locator.sheetId : undefined;
    return normalizedCellAddress(label) === address
      && (!issue.sheetId || !sheetId || issue.sheetId === sheetId);
  });
  return binding
    ? instance.model.fields.find((field) => field.bindingId === binding.bindingId)
    : undefined;
}
