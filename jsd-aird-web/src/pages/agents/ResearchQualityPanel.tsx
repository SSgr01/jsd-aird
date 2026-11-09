import { Button, Space } from 'antd';
import type { ResearchMatrix, ReportQualityReview, SourceQuote } from '@/services/agents/agent-api';
import { reviewProblem } from './research-presentation';

const verdicts: Record<string, string> = {
  SUPPORTED: '原文支持',
  CONTRADICTED: '与原文不一致',
  INSUFFICIENT: '缺少原文依据',
  NON_FACTUAL: '说明文字',
  COVERED: '已回答',
  EXPLICIT_GAP: '已说明缺少的资料',
  MISSING: '尚未回答',
};
const findings: Record<string, string> = {
  DOCUMENT_WIDE_ABSENCE_NOT_ESTABLISHED_BY_SOURCE_SPANS:
    '目前阅读的段落不足以证明整篇文献没有相关数据，请补充依据或缩小结论范围。',
  SOURCE_UNCERTAINTY_NOT_PRESERVED: '原文中的不确定性没有完整保留，请调整对应结论。',
};
export function ResearchQualityPanel({
  matrix,
  review,
  onQuote,
  frozen = false,
  reportExpected = true,
}: {
  matrix?: ResearchMatrix;
  review?: ReportQualityReview;
  onQuote: (quote: SourceQuote) => void;
  frozen?: boolean;
  reportExpected?: boolean;
}) {
  const rows = Object.values(matrix?.questions || {});
  const spans = new Map(
    rows.flatMap((row) => row.claims.flatMap((claim) => claim.quotes)).map((q) => [q.quote_id, q]),
  );
  const current =
    review?.quality_check_version === 3 && review.matrix_revision === matrix?.revision;
  const passed =
    current &&
    review?.passed &&
    !review.blocking_findings?.length &&
    review.verification === 'AI_REVIEW_NOT_HUMAN_VERIFICATION';
  return (
    <section className="research-quality" aria-label={reportExpected ? '报告检查' : '已找到的依据'}>
      {reportExpected && <h3>{passed ? '报告检查' : '需要补充的内容'}</h3>}
      {reportExpected && (
        <p>
          {passed
            ? '已完成 AI 辅助检查，使用前仍需人工复核。'
            : !current
              ? '报告尚未完成检查，请先核对相关内容。'
              : reviewProblem(review) + '。'}
        </p>
      )}
      {current &&
        review?.blocking_findings?.map((finding, index) => (
          <p key={index}>{findings[finding.reason] || '这段内容尚未通过检查，请核对相关原文。'}</p>
        ))}
      {current &&
        review?.units
          .filter((unit) => !['SUPPORTED', 'NON_FACTUAL'].includes(unit.verdict))
          .map((unit, index) => (
            <div className="research-check-item" key={unit.unit_id}>
              <strong>
                第 {index + 1} 项 · {verdicts[unit.verdict] || '需要核对'}
              </strong>
              <p>{unit.reason}</p>
              <Space wrap>
                {unit.quote_ids.map(
                  (id) =>
                    spans.has(id) && (
                      <Button key={id} onClick={() => onQuote(spans.get(id)!)}>
                        预览相关段落
                      </Button>
                    ),
                )}
              </Space>
            </div>
          ))}
      {current &&
        review?.questions
          .filter((item) => item.verdict === 'MISSING')
          .map((item) => (
            <div key={item.question_id} className="research-check-item">
              <strong>{matrix?.questions[item.question_id]?.question || '尚未回答的问题'}</strong>
              <p>{item.reason}</p>
            </div>
          ))}
      {!!rows.length && <h3>{frozen ? '报告参考内容' : '已找到的依据'}</h3>}
      {rows.map((row) => (
        <section className="research-check-item" key={row.question_id}>
          <h4>{row.question}</h4>
          {row.claims.map((claim, index) => (
            <div key={index}>
              <p>{claim.claim}</p>
              {claim.qualifiers && (
                <p className="harness-muted">
                  {Object.entries(claim.qualifiers)
                    .map(([k, v]) => `${k}：${v}`)
                    .join('；')}
                </p>
              )}
              {claim.quotes.map((quote) => (
                <div key={quote.quote_id} className="research-quality-quote">
                  <blockquote>{quote.text}</blockquote>
                  <Button type="link" onClick={() => onQuote(quote)}>
                    预览 · {quote.source.title}
                  </Button>
                </div>
              ))}
            </div>
          ))}
          {row.gap_note && <p>还需补充：{row.gap_note}</p>}
        </section>
      ))}
    </section>
  );
}
