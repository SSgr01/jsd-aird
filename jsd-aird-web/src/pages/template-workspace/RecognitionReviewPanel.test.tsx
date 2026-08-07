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
    matrix: 0,
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

  it('把结构区域和协议恢复候选分开展示', () => {
    render(
      <RecognitionReviewPanel
        review={{
          ...review,
          semanticModel: {
            businessBlocks: [
              {
                blockId: 'block-text',
                sheetId: 'sheet-1',
                range: 'H6:J23',
                type: 'FREE_TEXT',
                businessName: '操作程序记录',
              },
            ],
          },
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

    expect(screen.getByText('先识别出的区域结构')).toBeInTheDocument();
    expect(screen.getByText('说明文本区')).toBeInTheDocument();
    expect(screen.getByText('操作程序记录')).toBeInTheDocument();
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

    expect(screen.getByText('本次识别部分内容未完成，已识别字段仍可确认，也可以重新识别或手工补充字段。'))
      .toBeInTheDocument();
    expect(screen.queryByText('部分字段关系需要核对')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '应用建议' })).not.toBeInTheDocument();
  });

  it('普通表格也展示长表预览和运行时列槽位', () => {
    render(
      <RecognitionReviewPanel
        review={{
          ...review,
          items: [{
            ...review.items[0]!,
            kind: 'ROW_TABLE',
            payload: {
              ...review.items[0]!.payload,
              longTableModel: {
                schemaVersion: 1,
                sourceKind: 'ROW_TABLE',
                semanticMode: 'LONG_FORM',
                sourceRange: 'A5:N26',
                rowHeaderRange: 'A5:D26',
                columnHeaderRange: 'E4:N4',
                dataRange: 'E5:N26',
                aggregatePolicy: 'INCLUDE_MARKED',
                blankAxisPolicy: 'SKIP_EMPTY_RUNTIME_MEMBER',
                trainingPolicy: 'REQUIRE_RUNTIME_MEMBER',
                dimensions: [],
                records: [{
                  recordKey: 'sheet-1|5|E5',
                  rowIndex: 5,
                  columnIndex: 5,
                  rowRole: 'TEST_ITEM',
                  rowPath: ['物性测试', '外观'],
                  columnMember: {
                    coordinate: 'E',
                    address: 'E4',
                    label: '',
                    status: 'RUNTIME_INPUT',
                    instanceStatus: 'EMPTY',
                  },
                  value: {
                    address: 'E5',
                    valueSource: 'USER_INPUT',
                    trainingEligible: false,
                  },
                  trainingEligible: false,
                }],
                columnSlots: [{
                  slotId: 'column-E',
                  column: 'E',
                  identityAddress: 'E4',
                  recordRange: 'E4:E26',
                  templateStatus: 'RUNTIME_INPUT',
                  instanceStatus: 'EMPTY',
                }],
              },
            },
          }],
        }}
        editable
        selectedRecognitionItemId="item-1"
        {...handlers}
      />,
    );

    expect(screen.getByText('长表结构预览')).toBeInTheDocument();
    expect(screen.getByText(/1 个运行时列槽位/)).toBeInTheDocument();
    expect(screen.getByText('空白输入')).toBeInTheDocument();
  });

  it('矩阵表展示共享列轴和交叉结果区，而不是普通明细表描述', () => {
    const slots = Array.from({ length: 10 }, (_, index) => {
      const column = String.fromCharCode('E'.charCodeAt(0) + index);
      return {
        slotId: `column-${column}`,
        column,
        identityAddress: `${column}4`,
        recordRange: `${column}4:${column}100`,
        templateStatus: 'RUNTIME_INPUT' as const,
        instanceStatus: 'EMPTY' as const,
      };
    });
    render(
      <RecognitionReviewPanel
        review={{
          ...review,
          summary: { ...review.summary, total: 1, pending: 1, scalar: 0, matrix: 1 },
          items: [{
            ...review.items[0]!,
            fieldName: '综合测试结果区域',
            kind: 'MATRIX',
            payload: {
              ...review.items[0]!.payload,
              fieldName: '综合测试结果区域',
              matrixModel: {
                semanticMode: 'CROSS_TAB',
                recordAxis: 'COLUMN',
                columnHeaderRange: 'E4:N4',
                rowHeaderRange: 'A5:D100',
                crossDataRange: 'E5:N100',
                columnSlots: slots,
              },
            },
          }],
        }}
        editable
        selectedRecognitionItemId="item-1"
        {...handlers}
      />,
    );

    expect(screen.getAllByText('综合测试结果区域')).toHaveLength(2);
    expect(screen.getByText('E4:N4')).toBeInTheDocument();
    expect(screen.getByText('A5:D100', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('E5:N100')).toBeInTheDocument();
    expect(screen.getByText('10 个', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('类型：交叉测试表 · 记录方向：按列')).toBeInTheDocument();
    expect(screen.queryByText('填写方向：按行填写')).not.toBeInTheDocument();
  });
});
