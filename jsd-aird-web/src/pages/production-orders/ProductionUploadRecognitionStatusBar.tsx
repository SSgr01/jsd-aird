import { ExperimentOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Progress, Space, Typography } from 'antd';

/**
 * The recognition strip the template center shows above its editor, reused for a
 * production order so both screens read the same way: how many fields were
 * recognised, how many still need confirming, and the three actions that move the
 * review forward.
 *
 * <p>It is pure presentation — the template center keeps its own copy of this
 * strip inside its page — so it takes counts and callbacks only.</p>
 *
 * <p>While recognition runs the strip reports the stage and percentage in place,
 * mirroring the template center, and locks every action that would conflict with
 * the result being rebuilt.  Recognising reuses the pending tone on purpose: it is
 * a transient state of the same review, so no stylesheet rule has to change.</p>
 */
export function ProductionUploadRecognitionStatusBar({
  fieldCount,
  pendingCount,
  pendingRegionCount,
  conflictCount,
  regionCount,
  busy,
  canUpdate,
  canRestart,
  onStart,
  onConfirmAll,
  onRestart,
  running = false,
  progress,
  stageLabel,
}: {
  fieldCount: number;
  pendingCount: number;
  pendingRegionCount: number;
  conflictCount: number;
  regionCount: number;
  busy: boolean;
  canUpdate: boolean;
  canRestart: boolean;
  onStart: () => void;
  onConfirmAll: () => void;
  onRestart: () => void;
  /** Recognition is running: show progress and lock the review actions. */
  running?: boolean;
  /** Completion percentage while running. */
  progress?: number;
  /** Stage wording while running. */
  stageLabel?: string;
}) {
  const pending = pendingCount > 0 || pendingRegionCount > 0;
  const tone = running
    ? 'pending'
    : conflictCount > 0
      ? 'conflict'
      : pending
        ? 'pending'
        : 'complete';
  const locked = running || busy;
  return (
    <div
      className="recognition-status-bar"
      data-tone={tone}
      role="status"
    >
      <div className="recognition-status-content">
        <ExperimentOutlined />
        {running ? (
          <>
            <Typography.Text strong>正在重新识别生产单</Typography.Text>
            <Typography.Text type="secondary">
              {`${stageLabel || '正在识别'} · 识别进度 ${progress ?? 0}%`}
            </Typography.Text>
            <Progress
              className="recognition-inline-progress"
              percent={progress ?? 0}
              showInfo={false}
              status="active"
            />
          </>
        ) : (
          <>
            <Typography.Text strong>{pending ? 'AI 识别待确认' : 'AI 识别已确认'}</Typography.Text>
            <Typography.Text type="secondary">
              {`区域 ${regionCount} ｜ 字段 ${fieldCount} ｜ 待确认区域 ${pendingRegionCount} ｜ 待确认字段 ${pendingCount} ｜ 结构冲突 ${conflictCount}`}
            </Typography.Text>
          </>
        )}
      </div>
      <Space size={6}>
        <Button
          size="small"
          disabled={locked || !canUpdate || !pending}
          loading={busy}
          onClick={onStart}
        >
          开始确认
        </Button>
        <Button
          size="small"
          type="primary"
          disabled={locked || !canUpdate || pendingCount === 0}
          loading={busy}
          onClick={onConfirmAll}
        >
          一键确认全部字段
        </Button>
        <Button
          size="small"
          icon={<ReloadOutlined />}
          disabled={locked || !canRestart}
          loading={running || busy}
          onClick={onRestart}
        >
          重新识别
        </Button>
      </Space>
    </div>
  );
}