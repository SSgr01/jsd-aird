import type { DataJob, DataSourceFile } from './data-api';

type DataProgressSource = Pick<DataJob | DataSourceFile, 'status' | 'progress'>;

const PARSE_COMPLETE_STATUSES = new Set([
  'WAITING_SHEET',
  'WAITING_MAPPING',
  'VALIDATING',
  'WAITING_CONFIRM',
  'COMMITTING',
  'COMPLETED',
]);

export function dataParseProgress(job: DataProgressSource) {
  if (PARSE_COMPLETE_STATUSES.has(job.status)) return 100;
  if (job.status === 'CREATED' || job.status === 'QUEUED') return 0;
  return Math.min(100, Math.max(0, Math.round(job.progress)));
}

export function dataParseStageLabel(job: Pick<DataJob, 'status' | 'currentStage'>) {
  if (PARSE_COMPLETE_STATUSES.has(job.status)) return '解析完成';
  if (job.status === 'FAILED') return '解析失败';
  if (job.status === 'CREATED' || job.status === 'QUEUED') return '等待解析';
  return switchStage(job.currentStage);
}

function switchStage(stage?: string) {
  switch (stage) {
    case 'READING_SOURCE': return '正在读取文件';
    case 'ANALYZING_STRUCTURE': return '正在分析结构';
    case 'EXTRACTING_DATA': return '正在提取数据';
    case 'SAVING_PARSED_DATA': return '正在保存解析结果';
    default: return '正在解析';
  }
}
