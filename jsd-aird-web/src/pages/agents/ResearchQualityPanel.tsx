import { Alert, Button, Collapse, Empty, List, Space, Typography } from 'antd';
import type { ResearchMatrix, ReportQualityReview, SourceQuote } from '@/services/agents/agent-api';

const verdicts: Record<string, string> = {
  SUPPORTED: '原文支持',
  CONTRADICTED: '与原文矛盾',
  INSUFFICIENT: '证据不足',
  NON_FACTUAL: '非事实性文字',
  COVERED: '已覆盖',
  EXPLICIT_GAP: '已说明本次缺口',
  MISSING: '未覆盖',
};

export function ResearchQualityPanel({
  matrix,
  review,
  onQuote,
  frozen = false,
}: {
  matrix?: ResearchMatrix;
  review?: ReportQualityReview;
  onQuote: (quote: SourceQuote) => void;
  frozen?: boolean;
}) {
  const rows = Object.values(matrix?.questions || {});
  const legacyReview = review && review.quality_check_version !== 2;
  const prescreen = review?.verification === 'DETERMINISTIC_PRESCREEN_NOT_AI_OR_HUMAN_VERIFICATION';
  const spans = new Map(
    rows.flatMap((row) => row.claims.flatMap((claim) => claim.quotes)).map((q) => [q.quote_id, q]),
  );
  return (
    <section
      className="research-quality"
      aria-label={frozen ? '报告冻结证据与核查' : '本次证据矩阵与核查'}
    >
      <Typography.Title level={5}>{frozen ? '报告冻结证据' : '子问题证据矩阵'}</Typography.Title>
      <Typography.Paragraph type="secondary">
        {frozen ? '生成时的矩阵快照' : '本次运行的持久化证据'} · 修订 {matrix?.revision ?? 0} ·{' '}
        {rows.length} 个子问题。 AI 候选抽取不是人工核对；本次证据缺口不代表文献未报告。
      </Typography.Paragraph>
      {!rows.length ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="尚未建立证据矩阵；旧报告不会自动补写核查记录"
        />
      ) : (
        <Collapse
          size="small"
          items={rows.map((row) => ({
            key: row.question_id,
            label: `${row.question} · ${row.claims.length ? '候选证据' : '本次证据缺口'}`,
            children: (
              <>
                {row.claims.length > 0 && (
                  <List
                    size="small"
                    locale={{ emptyText: null }}
                    dataSource={row.claims}
                    renderItem={(claim) => (
                      <List.Item>
                        <div>
                          <Typography.Paragraph>{claim.claim}</Typography.Paragraph>
                          {claim.qualifiers && (
                            <Typography.Paragraph type="secondary">
                              {Object.entries(claim.qualifiers)
                                .map(([k, v]) => `${k}：${v}`)
                                .join('；')}
                            </Typography.Paragraph>
                          )}
                          {claim.quotes.map((quote) => (
                            <div key={quote.quote_id} className="research-quality-quote">
                              <Typography.Paragraph>{quote.text}</Typography.Paragraph>
                              <Typography.Paragraph type="secondary">
                                {quote.source.title} · 发布 {quote.source.publication_id} · 位置{' '}
                                {quote.start}–{quote.end}
                              </Typography.Paragraph>
                              <Space wrap>
                                <Button size="small" onClick={() => onQuote(quote)}>
                                  定位原文
                                </Button>
                                <Typography.Text copyable={{ text: quote.quote_id }}>
                                  句柄 {quote.quote_id.slice(-8)}
                                </Typography.Text>
                              </Space>
                            </div>
                          ))}
                        </div>
                      </List.Item>
                    )}
                  />
                )}
                {row.gap_note && (
                  <Typography.Paragraph>本次缺口：{row.gap_note}</Typography.Paragraph>
                )}
              </>
            ),
          }))}
        />
      )}
      <Typography.Title level={5}>报告事实核查</Typography.Title>
      {!review ? (
        <Typography.Paragraph type="secondary">
          暂无对应此矩阵的 AI 核查记录；修改正文或矩阵后须重新核查。
        </Typography.Paragraph>
      ) : (
        <>
          <Alert
            type={review.passed && !legacyReview ? 'info' : 'warning'}
            showIcon
            message={
              legacyReview
                ? '旧版 AI 核查记录，须按新协议重新核查'
                : prescreen
                  ? '报告规则预检未通过，尚未调用 AI 核查'
                  : review.passed
                    ? 'AI 原文核查通过，仍需人工复核'
                    : frozen
                      ? '这份报告的核查未通过，请先修订'
                      : 'AI 原文核查未通过，报告保存已受阻'
            }
            description={`矩阵修订 ${review.matrix_revision}；AI 核查不是独立实验复现或人工已核对。`}
          />
          {!!review.blocking_findings?.length && (
            <List
              size="small"
              dataSource={review.blocking_findings}
              renderItem={(finding) => (
                <List.Item>
                  {finding.unit_id}
                  ：原文片段不能证明全文未报告。请补充依据或明确表述为本次证据缺口。
                </List.Item>
              )}
            />
          )}
          <Collapse
            size="small"
            items={[
              {
                key: 'review',
                label: `查看逐段与子问题核查（${review.units.length} 段）`,
                children: (
                  <>
                    <List
                      size="small"
                      dataSource={review.units}
                      renderItem={(unit) => (
                        <List.Item>
                          <div>
                            <Typography.Text strong>
                              {unit.unit_id} · {verdicts[unit.verdict] || unit.verdict}
                            </Typography.Text>
                            <Typography.Paragraph>{unit.reason}</Typography.Paragraph>
                            <Space wrap>
                              {unit.quote_ids.map(
                                (id) =>
                                  spans.has(id) && (
                                    <Button
                                      key={id}
                                      size="small"
                                      onClick={() => onQuote(spans.get(id)!)}
                                    >
                                      原文 {id.slice(-6)}
                                    </Button>
                                  ),
                              )}
                            </Space>
                          </div>
                        </List.Item>
                      )}
                    />
                    <List
                      size="small"
                      dataSource={review.questions}
                      renderItem={(question) => (
                        <List.Item>
                          {matrix?.questions[question.question_id]?.question ||
                            question.question_id}
                          ：{verdicts[question.verdict] || question.verdict}。{question.reason}
                        </List.Item>
                      )}
                    />
                  </>
                ),
              },
            ]}
          />
        </>
      )}
    </section>
  );
}
