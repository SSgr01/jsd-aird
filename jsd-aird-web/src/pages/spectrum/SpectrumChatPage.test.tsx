import { fireEvent, render, screen, waitFor } from '@testing-library/react';

import { AssistantResult, StreamingSpectrumResult } from './SpectrumChatPage';

describe('AssistantResult', () => {
  it('renders the compact presentation and keeps internal evidence ids hidden', async () => {
    render(
      <AssistantResult
        result={{
          analysisStatus: 'SUCCEEDED',
          confidence: 'LOW',
          evidenceSufficiency: 'INSUFFICIENT_FOR_MAPPING',
          answerMarkdown: '模型原始摘要不应重复显示。',
          presentation: {
            version: 1,
            primaryIntent: 'ATTRIBUTION',
            conclusion: '当前证据不足以支持明确归因。',
            keyFindings: [
              '新旧批次峰位基本一致。',
              '约 295-300 nm 处吸光度相差约 0.2 Abs。',
            ],
            validationSteps: ['在相同条件下进行平行复测。', '使用标准品进行对照测试。'],
            detailSectionKeys: ['candidateInterpretations', 'testConditionLimitations'],
          },
          candidateInterpretations: [
            {
              feature: '约 295-300 nm 主峰',
              interpretation: '可能对应延伸共轭体系。',
              confidence: 'LOW',
              evidenceIds: ['4d51dc2d-2124-4ab4-9208-5bc52c8e783c/page-1'],
            },
          ],
          testConditionLimitations: ['缺少比色皿光程信息。'],
          conclusionBoundary: 'POSSIBLE_INTERPRETATIONS_ONLY_NO_DEFINITIVE_FORMULA',
        }}
        warnings={[]}
        citations={[
          {
            chartId: 'chart-a',
            category: 'UV',
            page: 1,
            title: 'E2E USP-096 新旧紫外对比',
          },
        ]}
        onPreview={() => undefined}
      />,
    );

    expect(screen.getByText('当前证据不足以支持明确归因。')).toBeInTheDocument();
    expect(screen.getByText('归因依据')).toBeInTheDocument();
    expect(screen.queryByText('部分结果已收敛')).not.toBeInTheDocument();
    expect(screen.queryByText('待专业人员复核')).not.toBeInTheDocument();
    expect(screen.queryByText('低置信度')).not.toBeInTheDocument();
    expect(screen.queryByText('不足以建立峰位映射')).not.toBeInTheDocument();
    expect(screen.getAllByText('建议验证实验')).toHaveLength(1);
    expect(screen.queryByText('图谱分析结果')).not.toBeInTheDocument();
    expect(screen.queryByText('模型原始摘要不应重复显示。')).not.toBeInTheDocument();
    expect(screen.queryByText(/4d51dc2d/)).not.toBeInTheDocument();
    expect(screen.queryByText('约 295-300 nm 主峰')).not.toBeInTheDocument();

    fireEvent.click(screen.getByText('查看分析详情'));

    expect(await screen.findByText('约 295-300 nm 主峰')).toBeInTheDocument();
    expect(screen.getByText('可能解释')).toBeInTheDocument();
    expect(screen.queryByText('Feature')).not.toBeInTheDocument();
    expect(screen.queryByText('Evidence Ids')).not.toBeInTheDocument();
    expect(screen.queryByText('LOW')).not.toBeInTheDocument();
    expect(screen.queryByText(/4d51dc2d/)).not.toBeInTheDocument();
    expect(screen.getAllByText('建议验证实验')).toHaveLength(1);
    expect(screen.getAllByText('依据图谱')).toHaveLength(1);
  });

  it('hides status tags while retaining partial-result warnings', () => {
    render(
      <AssistantResult
        result={{
          analysisStatus: 'PARTIAL',
          confidence: 'LOW',
          evidenceSufficiency: 'INSUFFICIENT_FOR_MAPPING',
          answerMarkdown: '当前证据不足，需要补充验证。',
        }}
        warnings={['图谱证据不足，结果已按边界收敛。']}
        citations={[]}
        onPreview={() => undefined}
      />,
    );

    expect(screen.queryByText('部分结果已收敛')).not.toBeInTheDocument();
    expect(screen.queryByText('待专业人员复核')).not.toBeInTheDocument();
    expect(screen.queryByText('低置信度')).not.toBeInTheDocument();
    expect(screen.queryByText('不足以建立峰位映射')).not.toBeInTheDocument();
    expect(screen.getByText('部分结果已按证据边界收敛')).toBeInTheDocument();
    expect(screen.getByText('图谱证据不足，结果已按边界收敛。')).toBeInTheDocument();
  });

  it('keeps the failure message without status tags', () => {
    render(
      <AssistantResult
        result={{
          analysisStatus: 'FAILED',
          errorMessage: '模型未返回有效分析结果，请重新分析。',
          confidence: 'LOW',
          evidenceSufficiency: 'INSUFFICIENT_FOR_MAPPING',
        }}
        warnings={[]}
        citations={[]}
        onPreview={() => undefined}
      />,
    );

    expect(screen.getByText('图谱 AI 分析失败')).toBeInTheDocument();
    expect(screen.getByText('模型未返回有效分析结果，请重新分析。')).toBeInTheDocument();
    expect(screen.queryByText('部分结果已收敛')).not.toBeInTheDocument();
    expect(screen.queryByText('待专业人员复核')).not.toBeInTheDocument();
    expect(screen.queryByText('低置信度')).not.toBeInTheDocument();
    expect(screen.queryByText('不足以建立峰位映射')).not.toBeInTheDocument();
  });

  it('compacts a historical result without a presentation field', () => {
    render(
      <AssistantResult
        result={{
          analysisStatus: 'SUCCEEDED',
          answerMarkdown:
            '图片展示了新旧批次光谱。两条曲线整体一致。旧批次峰更高。当前证据不足。建议进行复测。',
          evidenceSufficiency: 'INSUFFICIENT_FOR_MAPPING',
          referenceAvailability: {
            hasSinglePeakReferences: false,
            statement: '当前材料不足以建立样品峰与单峰参考峰的映射。',
          },
          comparisons: [
            '新旧批次峰位基本一致。',
            '约 295-300 nm 处吸光度相差约 0.2 Abs。',
            '约 240 nm 处旧批次略高。',
            '320 nm 后曲线接近基线。',
          ],
          suggestedValidationExperiments: [
            '同条件平行复测。',
            '使用标准品对照。',
            '进行 HPLC 验证。',
            '核对原始称量记录。',
          ],
        }}
        warnings={[]}
        citations={[]}
        onPreview={() => undefined}
      />,
    );

    expect(
      screen.getByText('当前证据不足以支持明确归因，也不能建立可靠的峰位映射。'),
    ).toBeInTheDocument();
    expect(screen.getByText('新旧批次峰位基本一致。')).toBeInTheDocument();
    expect(screen.getByText('约 295-300 nm 处吸光度相差约 0.2 Abs。')).toBeInTheDocument();
    expect(screen.getByText('约 240 nm 处旧批次略高。')).toBeInTheDocument();
    expect(screen.queryByText('320 nm 后曲线接近基线。')).not.toBeInTheDocument();
    expect(screen.getByText('同条件平行复测。')).toBeInTheDocument();
    expect(screen.queryByText('核对原始称量记录。')).not.toBeInTheDocument();
    expect(screen.queryByText(/图片展示了新旧批次光谱/)).not.toBeInTheDocument();
  });

  it('collapses details again when the displayed result changes', async () => {
    const firstResult = {
      analysisStatus: 'SUCCEEDED' as const,
      presentation: {
        version: 1,
        primaryIntent: 'FEATURE_INTERPRETATION' as const,
        conclusion: '第一条结论。',
        keyFindings: [],
        validationSteps: [],
        detailSectionKeys: ['candidateInterpretations'],
      },
      candidateInterpretations: [{ feature: '第一条特征峰', interpretation: '第一条解释。' }],
    };
    const { rerender } = render(
      <AssistantResult
        result={firstResult}
        warnings={[]}
        citations={[]}
        onPreview={() => undefined}
      />,
    );

    fireEvent.click(screen.getByText('查看分析详情'));
    expect(await screen.findByText('第一条特征峰')).toBeInTheDocument();

    rerender(
      <AssistantResult
        result={{
          ...firstResult,
          presentation: { ...firstResult.presentation, conclusion: '第二条结论。' },
          candidateInterpretations: [
            { feature: '第二条特征峰', interpretation: '第二条解释。' },
          ],
        }}
        warnings={[]}
        citations={[]}
        onPreview={() => undefined}
      />,
    );

    await waitFor(() => expect(screen.getByText('第二条特征峰')).not.toBeVisible());
  });

  it('never exposes a persisted reasoning summary', () => {
    // Simulate an already-persisted V2 payload that predates summary removal.
    const legacyPresentation = {
      version: 2,
      primaryIntent: 'ATTRIBUTION' as const,
      conclusion: '当前证据不足以支持明确归因。',
      keyFindings: [],
      validationSteps: [],
      detailSectionKeys: [],
      reasoningSummary: ['先核对坐标轴和图例，再比较约 235 nm 与 295 nm 的可见峰。'],
    };
    render(
      <AssistantResult
        result={{
          analysisStatus: 'SUCCEEDED',
          answerMarkdown: '当前证据不足以支持明确归因。',
          presentation: legacyPresentation,
        }}
        warnings={[]}
        citations={[]}
        onPreview={() => undefined}
      />,
    );

    expect(screen.queryByText('分析思路摘要')).not.toBeInTheDocument();
    expect(screen.queryByText(/先核对坐标轴和图例/)).not.toBeInTheDocument();
  });

  it('keeps an explicit in-progress state after the safe answer appears', () => {
    render(<StreamingSpectrumResult answer="当前图片不足以支持明确化学归因。" />);

    expect(screen.getByText('当前图片不足以支持明确化学归因。')).toBeVisible();
    expect(screen.getByText('完整分析仍在生成')).toBeVisible();
    expect(screen.getByText(/以下是已校验的阶段性结论/)).toBeVisible();
    expect(screen.queryByText('分析思路摘要')).not.toBeInTheDocument();
    expect(screen.queryByText(/Evidence Ids/i)).not.toBeInTheDocument();
  });

  it('uses an intent-specific heading for comparison answers', () => {
    render(
      <AssistantResult
        result={{
          analysisStatus: 'SUCCEEDED',
          presentation: {
            version: 2,
            primaryIntent: 'COMPARISON',
            conclusion: '两个批次的主峰位置一致，峰高略有差异。',
            keyFindings: ['差异集中在主峰区域。'],
            validationSteps: [],
            detailSectionKeys: [],
          },
        }}
        warnings={[]}
        citations={[]}
        onPreview={() => undefined}
      />,
    );

    expect(screen.getByText('主要差异')).toBeVisible();
    expect(screen.queryByText('关键依据')).not.toBeInTheDocument();
    expect(screen.queryByText('建议验证实验')).not.toBeInTheDocument();
  });
});
