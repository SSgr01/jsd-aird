const STORAGE_KEY = 'jsd-aird:knowledge-upload-tasks:v1';
const EVENT_NAME = 'jsd-aird:knowledge-upload-tasks-changed';
const MAX_AGE_MS = 24 * 60 * 60 * 1000;

export type KnowledgeUploadTaskStatus = 'STAGING' | 'CHECKING' | 'SUBMITTING' | 'QUEUED' | 'DUPLICATE' | 'FAILED';

export interface KnowledgeUploadTask {
  id: string;
  fileName: string;
  size: number;
  status: KnowledgeUploadTaskStatus;
  progress: number;
  documentId?: string;
  message?: string;
  updatedAt: number;
}

function canUseStorage() {
  return typeof window !== 'undefined' && typeof window.localStorage !== 'undefined';
}

function isTask(value: unknown): value is KnowledgeUploadTask {
  if (typeof value !== 'object' || value === null) return false;
  const item = value as Record<string, unknown>;
  return typeof item.id === 'string' && typeof item.fileName === 'string'
    && typeof item.size === 'number' && typeof item.status === 'string'
    && typeof item.progress === 'number' && typeof item.updatedAt === 'number';
}

function readTasks(): KnowledgeUploadTask[] {
  if (!canUseStorage()) return [];
  try {
    const parsed: unknown = JSON.parse(window.localStorage.getItem(STORAGE_KEY) || '[]') as unknown;
    if (!Array.isArray(parsed)) return [];
    const now = Date.now();
    return parsed.filter((item): item is KnowledgeUploadTask => isTask(item)
      && now - item.updatedAt < MAX_AGE_MS
      // Duplicate detection is a decision point, not a background job. Do not
      // keep a terminal duplicate card after the user has dismissed it.
      && item.status !== 'DUPLICATE');
  } catch {
    return [];
  }
}

function notify() {
  if (typeof window !== 'undefined') window.dispatchEvent(new Event(EVENT_NAME));
}

export function loadKnowledgeUploadTasks() {
  return readTasks();
}

export function saveKnowledgeUploadTasks(tasks: KnowledgeUploadTask[]) {
  if (canUseStorage()) window.localStorage.setItem(STORAGE_KEY, JSON.stringify(tasks.slice(-100)));
  notify();
}

export function updateKnowledgeUploadTask(task: KnowledgeUploadTask) {
  const tasks = readTasks().filter((item) => item.id !== task.id);
  saveKnowledgeUploadTasks([...tasks, task]);
}

export function removeKnowledgeUploadTask(id: string) {
  saveKnowledgeUploadTasks(readTasks().filter((item) => item.id !== id));
}

export function subscribeKnowledgeUploadTasks(listener: () => void) {
  if (typeof window === 'undefined') return () => undefined;
  const onChange = () => listener();
  window.addEventListener(EVENT_NAME, onChange);
  window.addEventListener('storage', onChange);
  return () => {
    window.removeEventListener(EVENT_NAME, onChange);
    window.removeEventListener('storage', onChange);
  };
}
