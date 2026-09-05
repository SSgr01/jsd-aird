import { fireEvent, render, screen } from '@testing-library/react';

import type { RecognitionReview } from '@/services/templates/template-api';

import { RecognitionReviewPanel } from './RecognitionReviewPanel';

const review: RecognitionReview = {
  recognitionRunId: 'run-1',
  runStatus: 'COMPLETED',
  summary: {
    total: 2,
    confirmed: 1,
    pending: 1,
    lowConfidence: 1,
    conflict: 0,
    ignored: 0,
    scalar: 2,
    rowTable: 0,
    columnTable: 0,
    qualityIssueCount: 0,
    autoFixedCount: 0,
    blockingIssueCount: 0,
  },
  groups: ['基础信息'],
  qualityIssues: [],
  items: [
    {
      id: 'item-1',
      suggestionIds: ['suggestion-1'],
      fieldName: '产品名称',
      description: '填写当前产品的业务名称',
      groupName: '基础信息',
      kind: 'SCALAR',
      valueType: 'string',
      sheetId: 'sheet-1',
      sheetName: 'Sheet1',
      labelAddress: 'A2',
      address: 'B2',
      confidence: 0.62,
      confidenceLevel: 'LOW',
      status: 'PENDING',
      payload: {
        fieldCode: 'product_name',
        fieldName: '产品名称',
        dataPath: '/basic/productName',
        valueType: 'string',
        required: false,
        role: 'FIELD',
        locatorType: 'CELL_RANGE',
        locator: { sheetId: 'sheet-1', address: 'B2' },
      },
    },
    {
      id: 'item-2',
      suggestionIds: ['suggestion-2'],
      fieldName: '生产日期',
      description: '填写生产日期',
      groupName: '基础信息',
      kind: 'SCALAR',
      valueType: 'date',
      sheetId: 'sheet-1',
      sheetName: 'Sheet1',
      labelAddress: 'A3',
      address: 'B3',
      confidence: 0.96,
      confidenceLevel: 'HIGH',
      status: 'CONFIRMED',
      payload: {
        fieldCode: 'production_date',
        fieldName: '生产日期',
        dataPath: '/basic/productionDate',
        valueType: 'date',
        required: false,
        role: 'FIELD',
        locatorType: 'CELL_RANGE',
        locator: { sheetId: 'sheet-1', address: 'B3' },
      },
    },
  ],
};

const handlers = {
  onSelect: vi.fn(),
  onConfirm: vi.fn(),
  onModify: vi.fn(),
  onIgnore: vi.fn(),
  onRestore: vi.fn(),
  onSelectQualityIssue: vi.fn(),
  onApplyQualityIssue: vi.fn(),
  onIgnoreQualityIssue: vi.fn(),
  onRollbackQualityIssue: vi.fn(),
};

describe('RecognitionReviewPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('识别失败时说明工作簿和原有字段仍被保留', () => {
    render(
      <RecognitionReviewPanel
        review={{
          ...review,
          runStatus: 'FAILED',
          items: [],
          groups: [],
          summary: { ...review.summary, total: 0, confirmed: 0, pending: 0, lowConfidence: 0 },
        }}
        editable
        {...handlers}
      />,
    );

    expect(screen.getByText('智能识别未完成')).toBeInTheDocument();
    expect(screen.getByText('工作簿内容和原有字段已保留，可在上方重新识别整份工作簿。'))
      .toBeInTheDocument();
  });

  it('默认只显示紧凑行，并仅展开当前项', () => {
    const { rerender } = render(
      <RecognitionReviewPanel review={review} editable {...handlers} />,
    );

    expect(screen.getByRole('button', { name: /产品名称/ })).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('填写当前产品的业务名称')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '确认' })).not.toBeInTheDocument();

    rerender(
      <RecognitionReviewPanel
        review={review}
        editable
        selectedRecognitionItemId="item-1"
        {...handlers}
      />,
    );

    expect(screen.getByText('填写当前产品的业务名称')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '确认' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /产品名称/ })).toHaveAttribute('aria-expanded', 'true');
  });

  it('在筛选文字后显示对应数量', () => {
    render(<RecognitionReviewPanel review={review} editable {...handlers} />);

    const pendingFilter = screen.getByRole('button', { name: '待确认 1' });
    fireEvent.click(pendingFilter);
    expect(screen.getByText('产品名称')).toBeInTheDocument();
    expect(screen.queryByText('生产日期')).not.toBeInTheDocument();
  });

  it('不把模型原始区域摘要混入字段候选', () => {
    render(
      <RecognitionReviewPanel
        review={{
          ...review,
          semanticModel: { businessBlocks: [] },
          items: [{
            ...review.items[0]!,
            payload: { ...review.items[0]!.payload, candidateOnly: true, pendingReason: 'PROTOCOL_REVIEW_REQUIRED' },
          }],
        }}
        editable
        selectedRecognitionItemId="item-1"
        {...handlers}
      />,
    );

    expect(screen.queryByText('先识别出的区域结构')).not.toBeInTheDocument();
    expect(screen.queryByText('说明文本区')).not.toBeInTheDocument();
    expect(screen.queryByText('操作程序记录')).not.toBeInTheDocument();
    expect(screen.getByText('这是待确认候选，不是正式字段')).toBeInTheDocument();
  });

  it('展开规范建议时展示业务影响和修正前后预览', () => {
    const qualityReview: RecognitionReview = {
      ...review,
      summary: { ...review.summary, qualityIssueCount: 1 },
      qualityIssues: [{
        id: 'quality-1',
        issueType: 'MIXED_CELL_ROLES',
        severity: 'WARNING',
        confidence: 0.96,
        sheetId: 'sheet-1',
        sheetName: 'Sheet1',
        address: 'A36',
        title: '标题和正文写在同一个单元格',
        description: '系统认为可以无损拆分。',
        businessImpact: '混写会影响字段定位。',
        autoFixable: true,
        status: 'DETECTED',
        suggestedPatch: { operations: [
          { op: 'SET_CELL', address: 'A36', expectedValue: '结论：内容', value: '结论' },
          { op: 'SET_CELL', address: 'B36', expectedValue: '', value: '内容' },
        ] },
        inversePatch: {},
        evidence: [],
      }],
    };
    render(
      <RecognitionReviewPanel
        review={qualityReview}
        editable
        selectedQualityIssueId="quality-1"
        {...handlers}
      />,
    );

    expect(screen.getByText('混写会影响字段定位。')).toBeInTheDocument();
    expect(screen.getByText('结论：内容 ｜ 空白')).toBeInTheDocument();
    expect(screen.getByText('结论 ｜ 内容')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '应用建议' })).toBeEnabled();
  });

  it('展示 Word 表格方向异常而不把它当成 OTHER 隐藏', () => {
    render(
      <RecognitionReviewPanel
        review={{
          ...review,
          summary: { ...review.summary, qualityIssueCount: 1, blockingIssueCount: 1 },
          qualityIssues: [{
            id: 'word-direction-1',
            issueType: 'STRUCTURE_DIRECTION_UNCLEAR',
            severity: 'BLOCKER',
            confidence: 0.99,
            sheetId: '',
            sheetName: '',
            address: 'table-a',
            title: 'Word 表格记录方向不明确',
            description: '无法可靠判断每条记录按行还是按列重复。',
            businessImpact: '必须先确认结构。',
            autoFixable: false,
            status: 'DETECTED',
            suggestedPatch: {},
            inversePatch: {},
            evidence: [{
              nodeId: 'table-a',
              sourcePath: '/document[1]/body[1]/tbl[2]',
              rowCount: 3,
              columnCount: 3,
            }],
          }],
        }}
        editable
        selectedQualityIssueId="word-direction-1"
        {...handlers}
      />,
    );

    expect(screen.getByText('Word 表格记录方向不明确')).toBeInTheDocument();
    expect(screen.getByText('必须先确认结构。')).toBeInTheDocument();
  });

  it('隐藏内部恢复诊断并对部分识别显示统一提示', () => {
    render(
      <RecognitionReviewPanel
        review={{
          ...review,
          runStatus: 'PARTIAL',
          summary: { ...review.summary, qualityIssueCount: 1 },
          qualityIssues: [{
            id: 'recovery-1',
            issueType: 'FIELD_RELATION_UNCLEAR',
            severity: 'WARNING',
            confidence: 1,
            sheetId: 'sheet-1',
            sheetName: 'Sheet1',
            address: 'A1',
            title: '部分字段关系需要核对',
            description: '内部协议恢复信息',
            businessImpact: '仅供诊断',
            autoFixable: false,
            status: 'DETECTED',
            suggestedPatch: {},
            inversePatch: {},
            evidence: [],
          }],
        }}
        editable
        {...handlers}
      />,
    );

    expect(screen.getByText('部分内容未完成，可重新识别或手工补充。'))
      .toBeInTheDocument();
    expect(screen.queryByText('部分字段关系需要核对')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '应用建议' })).not.toBeInTheDocument();
  });

  it('把多个互补区域展示为一个模型分区方案', () => {
    const conflictItem: RecognitionReview['items'][number] = {
      ...review.items[0]!,
      id: 'structure-item',
      suggestionIds: ['physical', 'form', 'rows'],
      fieldName: '结构候选',
      kind: 'COLUMN_TABLE',
      status: 'CONFLICT',
      payload: {
        ...review.items[0]!.payload,
        candidateOnly: true,
        structureConflict: true,
        resolutionGroupId: 'structure-conflict-1',
        structureAlternatives: [
          {
            alternativeId: 'physical-option',
            source: 'PHYSICAL',
            regions: [
              { suggestionId: 'physical', kind: 'COLUMN_TABLE', range: 'A4:J6' },
            ],
          },
          {
            alternativeId: 'model-partition',
            source: 'MODEL',
            regions: [
              { suggestionId: 'form', kind: 'FORM_REGION', range: 'A1:J5' },
              { suggestionId: 'rows', kind: 'ROW_TABLE', range: 'A6:J22' },
            ],
          },
        ],
      },
    };
    render(
      <RecognitionReviewPanel
        review={{
          ...review,
          items: [conflictItem],
          summary: { ...review.summary, total: 1, pending: 0, conflict: 1 },
        }}
        editable
        selectedRecognitionItemId="structure-item"
        {...handlers}
      />,
    );

    expect(screen.getByText('系统发现 2 种结构方案')).toBeInTheDocument();
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '选择结构方案' }));
    fireEvent.click(screen.getByText('模型候选：字段区 A1:J5 + 按行明细表 A6:J22'));
    fireEvent.click(screen.getByRole('button', { name: '确认' }));

    expect(handlers.onConfirm).toHaveBeenCalledWith(conflictItem, 'model-partition');
  });

  it('按区域、字段、属性三级展示，并将槽位和审计候选分离', () => {
    const root: RecognitionReview['items'][number] = {
      ...review.items[0]!,
      id: 'region-root',
      suggestionIds: ['region-root-suggestion'],
      fieldName: '光引发剂测试表',
      kind: 'COLUMN_TABLE',
      status: 'CONFIRMED',
      payload: {
        ...review.items[0]!.payload,
        fieldName: '光引发剂测试表',
        kind: 'COLUMN_TABLE',
      },
    };
    const child: RecognitionReview['items'][number] = {
      ...review.items[0]!,
      id: 'field-1',
      suggestionIds: ['field-suggestion'],
      fieldName: '耐油笔',
      kind: 'SCALAR',
      status: 'PENDING',
      payload: {
        ...review.items[0]!.payload,
        fieldName: '耐油笔',
        nameSource: 'PHYSICAL_HEADER_FALLBACK',
        reviewRequired: true,
        regionId: 'region-1',
        locator: { sheetId: 'sheet-1', address: 'C4:H4', labelAddress: 'A4' },
      },
    };
    render(
      <RecognitionReviewPanel
        review={{
          ...review,
          items: [root, child],
          regions: [{
            regionId: 'region-1',
            blockId: 'region-1',
            kind: 'COLUMN_TABLE',
            sheetId: 'sheet-1',
            sheetName: 'Sheet1',
            range: 'A4:H19',
            fieldName: '光引发剂测试表',
            status: 'CONFIRMED',
            canonicalStatus: 'CONFIRMED',
            structureStatus: 'CONFIRMED',
            alternatives: [
              { alternativeId: 'physical', source: 'PHYSICAL', regions: [{ suggestionId: 'region-root-suggestion', kind: 'COLUMN_TABLE', range: 'A4:H19' }] },
              { alternativeId: 'model', source: 'MODEL', regions: [{ suggestionId: 'model-suggestion', kind: 'ROW_TABLE', range: 'A4:H19' }] },
            ],
            fields: [child],
            auditSuggestions: [{ ...root, id: 'audit-1', fieldName: '模型原始候选' }],
          }],
          statistics: {
            regionCount: 1,
            structureAlternativeCount: 2,
            structureConflictGroups: 1,
            fieldCount: 1,
            pendingFieldCount: 1,
            auditSuggestionCount: 2,
          },
        }}
        editable
        selectedRecognitionItemId="field-1"
        {...handlers}
      />,
    );

    expect(screen.getByText('光引发剂测试表')).toBeInTheDocument();
    expect(screen.getByText('结构方案（2）')).toBeInTheDocument();
    expect(screen.getAllByText('耐油笔').length).toBeGreaterThan(0);
    expect(screen.getByText('类型')).toBeInTheDocument();
    expect(screen.queryByText('名称依据')).not.toBeInTheDocument();
    expect(screen.queryByText('表头识别')).not.toBeInTheDocument();
    expect(screen.queryByText('审计信息（1）')).not.toBeInTheDocument();
  });

  it('同一 Word 区域保留所有稳定单元格候选并标记重复字段', () => {
    const wordFields = ['序号', '物料名称', '单位'].map((fieldName, index) => ({
      ...review.items[0]!,
      id: `word-field-${index}`,
      suggestionIds: [`word-suggestion-${index}`],
      fieldName,
      child: true,
      payload: {
        ...review.items[0]!.payload,
        fieldName,
        relationId: `word-relation-${index}`,
        regionId: 'word-row-region',
        blockId: 'word-row-region',
        parentRelationId: 'word-row-region',
        mappingKind: 'REPEAT_FIELD' as const,
        locatorType: 'DOCX_TABLE_CELL' as const,
        locator: {
          locatorType: 'DOCX_TABLE_CELL',
          nodeId: `docx-cell-${index}`,
          valueAnchor: `docx-cell-${index}`,
          valueCellPaths: [`/document[1]/body[1]/tbl[2]/tr[2]/tc[${index + 1}]`],
        },
      },
    }));
    render(
      <RecognitionReviewPanel
        review={{
          ...review,
          items: wordFields,
          regions: [{
            regionId: 'word-row-region',
            blockId: 'word-row-region',
            kind: 'ROW_TABLE',
            sheetId: '',
            sheetName: '',
            range: '/document[1]/body[1]/tbl[2]',
            fieldName: 'Word 按行明细',
            status: 'CONFIRMED',
            canonicalStatus: 'CONFIRMED',
            structureStatus: 'CONFIRMED',
            alternatives: [],
            fields: wordFields,
            auditSuggestions: [],
          }],
        }}
        editable
        {...handlers}
      />,
    );

    expect(screen.getByText('3 个')).toBeInTheDocument();
    for (const fieldName of ['序号', '物料名称', '单位']) {
      expect(screen.getByRole('button', { name: new RegExp(fieldName) })).toBeInTheDocument();
    }
    expect(screen.getAllByText(/明细字段/)).toHaveLength(3);
  });

  it('为单个未确认且没有字段的模型区域提供确认并识别字段入口', () => {
    const root = {
      ...review.items[0]!,
      id: 'form-root',
      suggestionIds: ['form-suggestion'],
      fieldName: '基本信息区域',
      kind: 'FORM_REGION' as const,
      status: 'PENDING' as const,
    };
    render(
      <RecognitionReviewPanel
        review={{
          ...review,
          items: [root],
          regions: [{
            regionId: 'form-region', blockId: 'form-region', kind: 'FORM_REGION',
            sheetId: 'sheet-1', sheetName: 'Sheet1', range: 'A1:H3', fieldName: '基本信息区域',
            status: 'PENDING', canonicalStatus: 'PROVISIONAL', structureStatus: 'UNRESOLVED',
            alternatives: [{ alternativeId: 'model-form', source: 'MODEL', regions: [{
              suggestionId: 'form-suggestion', kind: 'FORM_REGION', range: 'A1:H3',
            }] }],
            fields: [], auditSuggestions: [],
          }],
        }}
        editable
        {...handlers}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '确认该区域并识别字段' }));
    expect(handlers.onConfirm).toHaveBeenCalledWith(root, 'model-form');
  });

  it('已有当前字段时只确认区域，不重复显示识别字段动作', () => {
    const root = {
      ...review.items[0]!,
      id: 'row-root-current',
      suggestionIds: ['row-suggestion'],
      fieldName: '检测明细',
      kind: 'ROW_TABLE' as const,
      status: 'PENDING' as const,
      payload: {
        ...review.items[0]!.payload,
        kind: 'ROW_TABLE' as const,
        candidateOnly: true,
      },
    };
    render(
      <RecognitionReviewPanel
        review={{
          ...review,
          items: [root, review.items[0]!],
          regions: [{
            regionId: 'row-region-current', blockId: 'row-region-current', kind: 'ROW_TABLE',
            sheetId: 'sheet-1', sheetName: 'Sheet1', range: 'A7:J21', fieldName: '检测明细',
            status: 'PENDING', canonicalStatus: 'PROVISIONAL', structureStatus: 'CONFIRMED',
            alternatives: [{ alternativeId: 'row-current', source: 'PHYSICAL', regions: [{
              suggestionId: 'row-suggestion', kind: 'ROW_TABLE', range: 'A7:J21',
            }] }],
            fields: [review.items[0]!], auditSuggestions: [],
          }],
        }}
        editable
        {...handlers}
      />,
    );

    expect(screen.getByRole('button', { name: '确认该区域' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '确认该区域并识别字段' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '确认该区域' }));
    expect(handlers.onConfirm).toHaveBeenCalledWith(root, 'row-current');
  });
});
