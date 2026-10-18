import { describe, expect, it } from 'vitest';
import { confirmationBlockReason, sheetSummary, templateGroups } from './import-guidance';
import type { DataPreview, DataTemplateOption } from './data-api';

describe('data import guidance', () => {
  it('groups published template versions by category and keeps uncategorized versions visible', () => {
    const templates = [
      { versionId: 'a', name: '材料性能', templateCode: 'MAT', versionNo: 2, category: '物料', format: 'XLSX' },
      { versionId: 'b', name: '工艺参数', templateCode: 'PROC', versionNo: 1, format: 'XLSX' },
    ] as DataTemplateOption[];
    expect(templateGroups(templates)).toHaveLength(2);
    expect(templateGroups(templates, '物料')[0]?.options[0]?.value).toBe('a');
    expect(templateGroups(templates, '未分类')[0]?.options[0]?.value).toBe('b');
    expect(templateGroups(templates, '设备')).toEqual([]);
  });
  it('distinguishes an unselected sheet from a selected sheet with no executable mapping', () => {
    const preview = { sheets: [{ sheetId: 'one', selected: false }, { sheetId: 'two', selected: true }], mappings: [], rows: [], issues: [] } as unknown as DataPreview;
    expect(sheetSummary(preview, 'one').message).toBe('未选择导入');
    expect(sheetSummary(preview, 'two').message).toContain('未找到可执行映射');
  });
  it('explains blockers even when the parser reports waiting for confirmation', () => {
    const preview = { job: { status: 'WAITING_CONFIRM' }, issues: [{ severity: 'BLOCKER', status: 'OPEN' }] } as DataPreview;
    expect(confirmationBlockReason(preview)).toContain('1 项阻断问题');
    preview.issues[0]!.status = 'RESOLVED';
    expect(confirmationBlockReason(preview)).toBeUndefined();
  });
});
