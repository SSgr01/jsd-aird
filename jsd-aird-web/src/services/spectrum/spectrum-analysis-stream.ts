import { spectrumApi } from './spectrum-api';

export type SpectrumStreamConnection = 'CONNECTING' | 'STREAMING' | 'RECONNECTING' | 'COMPLETE';

export interface SpectrumAnalysisStreamState {
  analysisRunId: string;
  sessionId: string;
  status: string;
  stage: string;
  answer: string;
  lastEventId: number;
  connection: SpectrumStreamConnection;
  error?: string;
}

export type SpectrumAnalysisStreamMetadata = Omit<
  SpectrumAnalysisStreamState,
  'answer' | 'lastEventId'
>;

const terminalStatuses = new Set(['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED']);
const STREAM_UI_REFRESH_MS = 16;
const listeners = new Set<() => void>();
const streamListeners = new Map<string, Set<() => void>>();
const streamRefreshTimers = new Map<string, ReturnType<typeof setTimeout>>();
const connections = new Map<string, Promise<void>>();
let snapshot = new Map<string, SpectrumAnalysisStreamState>();
let metadataSnapshot = new Map<string, SpectrumAnalysisStreamMetadata>();

export function getSpectrumAnalysisStreams() {
  return snapshot;
}

export function subscribeSpectrumAnalysisStreams(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getSpectrumAnalysisStreamMetadata() {
  return metadataSnapshot;
}

export function subscribeSpectrumAnalysisStream(analysisRunId: string, listener: () => void) {
  const subscribers = streamListeners.get(analysisRunId) || new Set<() => void>();
  subscribers.add(listener);
  streamListeners.set(analysisRunId, subscribers);
  return () => {
    subscribers.delete(listener);
    if (!subscribers.size) streamListeners.delete(analysisRunId);
  };
}

export function isTerminalSpectrumAnalysisStatus(status: string | undefined) {
  return Boolean(status && terminalStatuses.has(status));
}

export function startSpectrumAnalysisStream(sessionId: string, analysisRunId: string) {
  const current = snapshot.get(analysisRunId);
  if (isTerminalSpectrumAnalysisStatus(current?.status)) return Promise.resolve();
  const active = connections.get(analysisRunId);
  if (active) return active;

  update(analysisRunId, {
    analysisRunId,
    sessionId,
    status: current?.status || 'QUEUED',
    stage: current?.stage || '正在读取图谱文件',
    answer: current?.answer || '',
    lastEventId: current?.lastEventId || 0,
    connection: 'CONNECTING',
    error: undefined,
  });
  const task = Promise.resolve().then(() => consumeAnalysisStream(sessionId, analysisRunId));
  connections.set(analysisRunId, task);
  void task.then(
    () => connections.delete(analysisRunId),
    () => connections.delete(analysisRunId),
  );
  return task;
}

async function consumeAnalysisStream(sessionId: string, analysisRunId: string) {
  let retryDelay = 300;
  while (true) {
    const current = snapshot.get(analysisRunId);
    if (isTerminalSpectrumAnalysisStatus(current?.status)) {
      update(analysisRunId, { connection: 'COMPLETE' });
      return;
    }
    try {
      update(analysisRunId, { connection: current?.lastEventId ? 'RECONNECTING' : 'CONNECTING' });
      await spectrumApi.streamAnalysis(
        analysisRunId,
        (event, data, eventId) => acceptEvent(sessionId, analysisRunId, event, data, eventId),
        current?.lastEventId || 0,
      );
      const afterStream = snapshot.get(analysisRunId);
      if (isTerminalSpectrumAnalysisStatus(afterStream?.status)) {
        update(analysisRunId, { connection: 'COMPLETE' });
        return;
      }
      const analysis = await spectrumApi.analysis(analysisRunId);
      if (isTerminalSpectrumAnalysisStatus(analysis.status)) {
        update(analysisRunId, {
          status: analysis.status,
          stage: analysis.currentStage || '分析完成',
          connection: 'COMPLETE',
        });
        return;
      }
      throw new Error('图谱事件流在任务完成前关闭');
    } catch (error) {
      try {
        const analysis = await spectrumApi.analysis(analysisRunId);
        if (isTerminalSpectrumAnalysisStatus(analysis.status)) {
          update(analysisRunId, {
            status: analysis.status,
            stage: analysis.currentStage || '分析完成',
            connection: 'COMPLETE',
            error: undefined,
          });
          return;
        }
      } catch {
        // Keep the durable stream alive; the next connection can resume from the last event id.
      }
      update(analysisRunId, {
        connection: 'RECONNECTING',
        error: error instanceof Error ? error.message : '图谱连接暂时中断',
      });
      await wait(retryDelay);
      retryDelay = Math.min(retryDelay * 2, 4000);
    }
  }
}

function acceptEvent(
  sessionId: string,
  analysisRunId: string,
  event: string,
  data: unknown,
  eventId?: string,
) {
  const current = snapshot.get(analysisRunId);
  const patch: Partial<SpectrumAnalysisStreamState> = { connection: 'STREAMING', error: undefined };
  const parsedId = eventId ? Number(eventId) : Number.NaN;
  if (Number.isSafeInteger(parsedId) && parsedId >= 0) patch.lastEventId = parsedId;
  if (event === 'answer_delta' && data && typeof data === 'object') {
    const delta = (data as { delta?: unknown }).delta;
    if (typeof delta === 'string' && delta) patch.answer = `${current?.answer || ''}${delta}`;
  } else if ((event === 'status' || event === 'snapshot') && data && typeof data === 'object') {
    const status = data as { status?: unknown; stage?: unknown };
    if (typeof status.status === 'string') patch.status = status.status;
    if (typeof status.stage === 'string') patch.stage = status.stage;
  } else if ((event === 'done' || event === 'error') && data && typeof data === 'object') {
    const status = data as { status?: unknown; stage?: unknown; message?: unknown };
    patch.status =
      typeof status.status === 'string'
        ? status.status
        : event === 'error'
          ? 'FAILED'
          : 'SUCCEEDED';
    patch.stage = typeof status.stage === 'string' ? status.stage : '分析完成';
    patch.connection = 'COMPLETE';
    if (event === 'error')
      patch.error = typeof status.message === 'string' ? status.message : patch.stage;
  }
  update(analysisRunId, {
    analysisRunId,
    sessionId,
    status: current?.status || 'QUEUED',
    stage: current?.stage || '正在读取图谱文件',
    answer: current?.answer || '',
    lastEventId: current?.lastEventId || 0,
    connection: current?.connection || 'CONNECTING',
    ...patch,
  });
}

function update(analysisRunId: string, patch: Partial<SpectrumAnalysisStreamState>) {
  const current = snapshot.get(analysisRunId);
  let updated: SpectrumAnalysisStreamState | undefined;
  if (current) {
    updated = { ...current, ...patch };
  }
  else if (
    patch.sessionId &&
    patch.status &&
    patch.stage &&
    patch.answer !== undefined &&
    patch.lastEventId !== undefined &&
    patch.connection
  ) {
    updated = patch as SpectrumAnalysisStreamState;
  }
  if (!updated) return;

  const metadataChanged = !current || !sameMetadata(current, updated);
  if (metadataChanged) {
    const next = new Map(snapshot);
    next.set(analysisRunId, updated);
    snapshot = next;
    const nextMetadata = new Map(metadataSnapshot);
    nextMetadata.set(analysisRunId, toMetadata(updated));
    metadataSnapshot = nextMetadata;
    listeners.forEach((listener) => listener());
    flushStreamNotification(analysisRunId);
  } else {
    // Answer text and replay cursors are read by the active per-run view. Keep
    // the page-level stream map stable so these deltas never invalidate it.
    snapshot.set(analysisRunId, updated);
    scheduleStreamNotification(analysisRunId);
  }
}

function toMetadata(state: SpectrumAnalysisStreamState): SpectrumAnalysisStreamMetadata {
  const { answer: _answer, lastEventId: _lastEventId, ...metadata } = state;
  return metadata;
}

function sameMetadata(left: SpectrumAnalysisStreamState, right: SpectrumAnalysisStreamState) {
  return left.sessionId === right.sessionId
    && left.status === right.status
    && left.stage === right.stage
    && left.connection === right.connection
    && left.error === right.error;
}

function scheduleStreamNotification(analysisRunId: string) {
  if (streamRefreshTimers.has(analysisRunId)) return;
  streamRefreshTimers.set(
    analysisRunId,
    setTimeout(() => {
      streamRefreshTimers.delete(analysisRunId);
      notifyStreamListeners(analysisRunId);
    }, STREAM_UI_REFRESH_MS),
  );
}

function flushStreamNotification(analysisRunId: string) {
  const timer = streamRefreshTimers.get(analysisRunId);
  if (timer !== undefined) {
    clearTimeout(timer);
    streamRefreshTimers.delete(analysisRunId);
  }
  notifyStreamListeners(analysisRunId);
}

function notifyStreamListeners(analysisRunId: string) {
  streamListeners.get(analysisRunId)?.forEach((listener) => listener());
}

function wait(milliseconds: number) {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}
