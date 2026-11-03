import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { agentApi, type LiteratureReport, type PrivateSource } from '@/services/agents/agent-api';
import { ConversationSources } from './ConversationSources';
import { ResearchOperations } from './ResearchOperations';
import { reportAssessment } from './research-presentation';

it('requires old passing reports to be reviewed under the current source policy', () => {
  const report = {
    evidence_matrix: { revision: 1 },
    quality_review: {
      passed: true,
      quality_check_version: 2,
      matrix_revision: 1,
      verification: 'AI_REVIEW_NOT_HUMAN_VERIFICATION',
    },
  } as LiteratureReport;
  expect(reportAssessment(report)).toMatchObject({ label: '待重新核查', passed: false });
  report.quality_review!.quality_check_version = 3;
  expect(reportAssessment(report)).toMatchObject({ label: 'AI 核查通过', passed: true });
});

const source: PrivateSource = {
  id: 'source-1',
  conversation_id: 'conversation-1',
  version_id: 'version-1',
  name: 'study.pdf',
  bytes: 100,
  content_type: 'application/pdf',
  sha256: 'a'.repeat(64),
  status: 'FAILED',
  kind: 'RUN_UPLOAD',
};

beforeEach(() => {
  window.matchMedia = (media) => ({
    matches: false,
    media,
    onchange: null,
    addListener() {},
    removeListener() {},
    addEventListener() {},
    removeEventListener() {},
    dispatchEvent: () => false,
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it('allows failed sources to be detached and blocks scope changes during execution', () => {
  const detach = vi.fn().mockResolvedValue(undefined);
  const props = {
    sources: [source],
    busy: false,
    active: false,
    onUpload: vi.fn(),
    onAcquire: vi.fn(),
    onDetach: detach,
  };
  const view = render(<ConversationSources {...props} />);
  expect(screen.getByText('解析失败')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '移出本会话' }));
  expect(detach).toHaveBeenCalledWith('source-1');
  view.rerender(<ConversationSources {...props} active />);
  expect(screen.getByRole('button', { name: '移出本会话' })).toBeDisabled();
  expect(screen.getByRole('button', { name: '上传资料（最多 10 MiB）' })).toBeDisabled();
});

it('only accepts an exact PMC identifier for explicit open-access acquisition', () => {
  const acquire = vi.fn().mockResolvedValue(undefined);
  render(
    <ConversationSources
      sources={[]}
      busy={false}
      active={false}
      onUpload={vi.fn()}
      onAcquire={acquire}
      onDetach={vi.fn()}
    />,
  );
  const input = screen.getByRole('textbox', { name: '开放获取文献 PMCID' });
  fireEvent.change(input, { target: { value: 'https://evil/' } });
  expect(screen.getByRole('button', { name: '获取开放全文' })).toBeDisabled();
  fireEvent.change(input, { target: { value: 'pmc10530767' } });
  fireEvent.click(screen.getByRole('button', { name: '获取开放全文' }));
  expect(acquire).toHaveBeenCalledWith('PMC10530767');
});

it('reconciles an unknown operation without offering a write replay', async () => {
  const operations = vi.spyOn(agentApi, 'operations').mockResolvedValue([
    {
      call_id: 'uncertain-1',
      tool_name: 'report_save',
      status: 'UNKNOWN',
      request_hash: 'hash',
      updated_at: '2026-10-31T00:00:00Z',
    },
  ]);
  const reconcile = vi
    .spyOn(agentApi, 'reconcile')
    .mockResolvedValue({ outcome: 'STILL_UNKNOWN', replayed: false });
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queries}>
      <App>
        <ResearchOperations runId="run-1" owner="owner-1" active={false} />
      </App>
    </QueryClientProvider>,
  );
  fireEvent.click(await screen.findByRole('button', { name: '核对回执' }));
  await waitFor(() => expect(reconcile).toHaveBeenCalledWith('run-1', 'uncertain-1'));
  expect(await screen.findByText('仍未取得权威回执，继续保留结果待核实状态。')).toBeInTheDocument();
  expect(screen.getByText('结果待核实')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /重试|重新执行/ })).not.toBeInTheDocument();
  expect(operations.mock.calls.length).toBeGreaterThanOrEqual(2);
  queries.clear();
});
