import { describe, expect, it } from 'vitest';
import type { DataIssue } from '@/services/data/data-api';
import type { WorkbookTemplateInstance } from '@/features/template-workspace/workbook-instance';
import { deduplicateFieldDefinitionIssues, fieldDefinitionIssueLocation } from './field-definition-review';

const instance = {
  model: { fields: [1, 2].map(n => ({ id: `sheet${n}.new`, bindingId: `b${n}`, name: '硬度', reviewStatus: 'CONFIRMED' })) },
  mapping: [1, 2].map(n => ({ bindingId: `b${n}`, locator: { sheetId: `s${n}`, sheetName: `表${n}`, labelRange: 'B73' } })),
} as unknown as WorkbookTemplateInstance;

const definitionIssue = (data: Partial<DataIssue>): DataIssue => ({
  id: 'legacy-issue', severity: 'WARNING', status: 'OPEN', issueType: 'FIELD_UNCONFIRMED', message: '待确认', detail: {}, ...data,
});

describe('legacy multi-sheet definition issues', () => {
  it('resolves a replaced field id using its physical sheet and cell', () => {
    const issue = definitionIssue({ sheetId: 's2', address: '$B$73', detail: { fieldId: 'old-id' } });
    expect(deduplicateFieldDefinitionIssues([issue], instance, [])).toEqual([]);
    expect(fieldDefinitionIssueLocation(issue, instance, [])).toEqual({ sheetId: 's2', sheetName: '表2', address: '$B$73' });
  });
  it('resolves a precursor without an address only within its physical sheet', () => {
    const issue = definitionIssue({ sheetId: 's2', message: '字段“硬度”的名称待确认', detail: { fieldId: 'old-id' } });
    expect(deduplicateFieldDefinitionIssues([issue], instance, [])).toEqual([]);
    expect(fieldDefinitionIssueLocation(issue, instance, [])?.address).toBe('B73');
  });
  it('keeps ambiguous warnings rather than resolving another sheet’s field', () => {
    const issue = definitionIssue({ address: 'B73', message: '字段“硬度”待确认' });
    expect(deduplicateFieldDefinitionIssues([issue], instance, [])).toEqual([issue]);
  });
});
