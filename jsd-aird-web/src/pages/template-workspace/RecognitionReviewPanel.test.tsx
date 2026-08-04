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
});
