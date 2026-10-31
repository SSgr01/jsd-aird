import { appEnv } from '@/app/config/env';
import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';
import { generateUUID } from '@/utils/uuid';

export interface SourceRef {
  kind:
    | 'KB_PUBLICATION'
    | 'RUN_UPLOAD'
    | 'EXTERNAL_FULLTEXT'
    | 'BUSINESS_RECORD'
    | 'DATA_SOURCE_FILE'
    | 'WEB_RESULT';
  resource_id?: string;
  resource_type?: string;
  project_id?: string;
  document_id: string;
  version_id: string;
  publication_id?: string;
  source_node_key: string;
  content_sha256: string;
  title: string;
  anchor: Record<string, unknown>;
  url?: string;
}
export interface Candidate {
  evidence_id: string;
  text: string;
  source: SourceRef;
  score?: number;
}
export interface ResearchTopic {
  id: string;
  revision: number;
  title: string;
  goal: string;
  scope_mode?: 'AUTOMATIC' | 'SELECTED';
  documents: Array<{ document_id: string; publication_id: string; title?: string }>;
  created_at: string;
  updated_at: string;
}
export interface EvidenceField {
  name: string;
  original: string;
  excerpt: string;
  value?: string;
  unit?: string;
  qualifiers?: Record<string, string>;
  source: SourceRef;
  status: 'AI_EXTRACTED' | 'EXTRACTION_VERIFIED' | 'REJECTED';
}
export interface EvidenceCard {
  id: string;
  revision: number;
  topic_id: string;
  instance: string;
  target: string;
  fields: EvidenceField[];
}
export interface LiteratureReport {
  id: string;
  revision: number;
  title: string;
  text: string;
  status: string;
  evidence: Record<string, Candidate>;
  evidence_matrix?: ResearchMatrix;
  quality_review?: ReportQualityReview;
}
export interface SourceQuote {
  quote_id: string;
  evidence_id: string;
  start: number;
  end: number;
  text: string;
  source: SourceRef;
}
export interface ResearchMatrix {
  revision: number;
  questions: Record<
    string,
    {
      question_id: string;
      question: string;
      status: string;
      documents: Array<{ document_id: string; publication_id: string }>;
      claims: Array<{ claim: string; quotes: SourceQuote[]; qualifiers?: Record<string, string> }>;
      gap_note: string;
    }
  >;
}
export interface ReportQualityReview {
  quality_check_version?: number;
  blocking_findings?: Array<{ unit_id: string; reason: string }>;
  passed: boolean;
  report_hash: string;
  matrix_revision: number;
  verification: string;
  units: Array<{ unit_id: string; verdict: string; quote_ids: string[]; reason: string }>;
  questions: Array<{ question_id: string; verdict: string; reason: string }>;
}
export interface Conversation {
  id: string;
  revision: number;
  topic_id?: string | null;
  profile?: 'agent' | 'fast_qa';
  title?: string;
  updated_at?: string;
  active_run?: string;
  last_run?: string;
  messages: ConversationEntry[];
  queued?: ConversationEntry[];
  queue_paused?: boolean;
}
export interface ConversationEntry {
  id: string;
  role: string;
  text: string;
  run_id?: string;
  status?: 'QUEUED' | 'PENDING' | 'DISPATCHED' | 'DELIVERED';
}
export interface QueueChange {
  action: 'edit' | 'delete' | 'move' | 'promote';
  message_id: string;
  text?: string;
  position?: number;
}
export interface AgentRun {
  id: string;
  conversation_id: string;
  topic_id?: string | null;
  profile?: 'agent' | 'fast_qa';
  status: string;
  steps: number;
  model_calls: number;
  tool_calls: number;
  last_text?: string;
  result?: string;
  error?: string;
  retryable?: boolean;
  request?: string;
  assistant_messages?: Array<{ id: string; text: string }>;
  plan?: TaskPlan;
  completion?: TaskCompletion;
  registered_outputs?: Array<{ id: string; kind: 'report' | 'card' | 'artifact'; title: string }>;
  source_documents?: Array<{ document_id: string; publication_id: string }>;
  evidence: Record<string, Candidate>;
  research_matrix?: ResearchMatrix;
  report_checks?: Record<string, ReportQualityReview>;
  pending_approval?: {
    id: string;
    tool: string;
    arguments: { query?: string };
    expires_at: number;
  };
}
export interface TaskPlan {
  goal: string;
  summary: string;
  task_type: 'RESEARCH' | 'GENERAL';
  presentation?: 'research_plan_v1';
  scope?: string;
  deliverables: Array<'answer' | 'report' | 'comparison' | 'analysis_file'>;
  steps: Array<{
    id: string;
    title: string;
    description?: string;
    status: 'pending' | 'in_progress' | 'completed';
  }>;
}
export interface TaskCompletion {
  outcome: 'COMPLETE' | 'PARTIAL' | 'NEEDS_INPUT';
  summary: string;
  checks: Array<{
    requirement: string;
    status: 'covered' | 'missing' | 'not_applicable';
    evidence_ids?: string[];
  }>;
  gaps: string[];
  verification: string;
}
export interface AgentEvent {
  schemaVersion: number;
  seq: number;
  runId: string;
  type: string;
  payload: Record<string, unknown>;
}

const base = '/api/v1/agents';
async function get<T>(path: string, params?: Record<string, unknown>) {
  return (await httpClient.get<ApiResponse<T>>(base + path, { params })).data.data;
}
async function post<T>(path: string, body: unknown, key?: string) {
  return (
    await httpClient.post<ApiResponse<T>>(base + path, body, {
      headers: { 'Idempotency-Key': key || generateUUID() },
    })
  ).data.data;
}

export const agentApi = {
  capabilities: () =>
    get<{
      enabled: boolean;
      engine: string;
      available?: boolean;
      rcs?: boolean;
      sandbox?: boolean;
    }>('/capabilities'),
  topics: () => get<ResearchTopic[]>('/research/topic'),
  topic: (id: string) => get<ResearchTopic>(`/research/topic/${id}`),
  createTopic: (title: string, goal: string) =>
    post<ResearchTopic>('/research/topic', { title, goal, documents: [] }),
  async updateTopic(
    topic: ResearchTopic,
    documents: ResearchTopic['documents'],
    scopeMode?: ResearchTopic['scope_mode'],
  ) {
    return (
      await httpClient.put<ApiResponse<ResearchTopic>>(base + `/research/topic/${topic.id}`, {
        documents,
        revision: topic.revision,
        ...(scopeMode ? { scope_mode: scopeMode } : {}),
      })
    ).data.data;
  },
  cards: (topicId: string) => get<EvidenceCard[]>('/research/card', { topicId }),
  reports: (topicId: string) => get<LiteratureReport[]>('/research/report', { topicId }),
  artifacts: (topicId: string) =>
    get<Array<{ id: string; name: string; bytes: number; status: string }>>('/research/artifact', {
      topicId,
    }),
  artifactUrl: (id: string) => appEnv.apiBaseUrl + base + `/research/artifact/${id}/content`,
  annotations: (topicId: string) =>
    get<Array<{ id: string; text: string; source?: SourceRef }>>('/research/annotation', {
      topicId,
    }),
  annotate: (topicId: string, text: string, source?: SourceRef) =>
    post('/research/annotation', { topic_id: topicId, text, source }),
  verify: (card: EvidenceCard, name: string, status: 'EXTRACTION_VERIFIED' | 'REJECTED') =>
    post(`/research/card/${card.id}/verify`, { revision: card.revision, name, status }),
  conversations: () => get<Conversation[]>('/conversations'),
  conversation: (id: string) => get<Conversation>(`/conversations/${id}`),
  run: (id: string) => get<AgentRun>(`/runs/${id}`),
  start: (
    topicId: string | undefined,
    request: string,
    conversation?: Conversation,
    resumeFrom?: string,
    key?: string,
    resumeQueue = false,
    profile?: 'agent' | 'fast_qa',
  ) =>
    post<AgentRun>(
      '/runs',
      {
        topic_id: topicId,
        request,
        conversation_id: conversation?.id,
        expected_revision: conversation?.revision,
        resume_from_run_id: resumeFrom,
        ...(resumeQueue ? { resume_queue: true } : {}),
        ...(profile ? { profile } : {}),
      },
      key,
    ),
  message: (
    conversation: Conversation,
    text: string,
    delivery: 'steer' | 'follow_up' = 'follow_up',
    key?: string,
  ) =>
    post(
      `/conversations/${conversation.id}/messages`,
      {
        text,
        delivery,
        expected_revision: conversation.revision,
      },
      key,
    ),
  queue: (conversation: Conversation, change: QueueChange, key?: string) =>
    post(
      `/conversations/${conversation.id}/queue`,
      { ...change, expected_revision: conversation.revision },
      key,
    ),
  cancel: (id: string) => post(`/runs/${id}/cancel`, {}),
  resume: (
    id: string,
    body: { retry?: boolean; approval_id?: string; decision?: 'APPROVED' | 'REJECTED' },
  ) => post(`/runs/${id}/resume`, body),
  workspace: (id: string) =>
    get<{ files: Array<{ path: string; bytes: number }> }>(`/runs/${id}/workspace`),
  wordUrl: (id: string) => appEnv.apiBaseUrl + base + `/research/report/${id}/word`,
  comparisonUrl: (id: string) => appEnv.apiBaseUrl + base + `/research/topic/${id}/comparison`,
  subscribe(id: string, accept: (event: AgentEvent) => void, failed: () => void) {
    const source = new EventSource(appEnv.apiBaseUrl + base + `/runs/${id}/events`, {
      withCredentials: true,
    });
    let cursor = 0;
    const receive = (event: MessageEvent<string>) => {
      let value: AgentEvent;
      try {
        value = JSON.parse(event.data) as AgentEvent;
        if (
          !value ||
          value.schemaVersion !== 1 ||
          value.runId !== id ||
          !Number.isSafeInteger(value.seq) ||
          value.seq < 1 ||
          typeof value.type !== 'string' ||
          !value.payload ||
          typeof value.payload !== 'object'
        )
          throw new Error('Invalid agent event');
      } catch {
        failed();
        return;
      }
      if (value.seq <= cursor) return;
      cursor = value.seq;
      accept(value);
      if (['run.succeeded', 'run.failed', 'run.cancelled'].includes(value.type)) source.close();
    };
    for (const name of [
      'run.queued',
      'message.delta',
      'message.started',
      'message.completed',
      'message.accepted',
      'message.applied',
      'conversation.queue',
      'tool.started',
      'tool.completed',
      'context.compaction.started',
      'context.compaction.completed',
      'model.retry.started',
      'model.retry.completed',
      'approval.required',
      'approval.ready',
      'model.auxiliary',
      'task.plan',
      'task.review',
      'research.activity',
      'research.matrix.updated',
      'research.report.checked',
      'run.succeeded',
      'run.failed',
      'run.cancelled',
    ])
      source.addEventListener(name, receive as EventListener);
    source.onerror = failed;
    return () => source.close();
  },
};

export function citedMarkdown(value: string) {
  const numbers = new Map<string, number>();
  return value.replace(/\[E:([a-zA-Z0-9_-]+)\]/g, (_, id: string) => {
    if (!numbers.has(id)) numbers.set(id, numbers.size + 1);
    return `[来源 ${numbers.get(id)}](#research-evidence-${id})`;
  });
}
