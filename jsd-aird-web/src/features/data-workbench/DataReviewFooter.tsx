import { Button, Typography } from 'antd';

export function DataReviewFooter({
  editable,
  confirmed,
  busy,
  blocked,
  saving,
  idleHint = '修改会自动保存；全部无误后完成核对',
  blockedHint = '请先处理上方待核对内容',
  onConfirm,
}: {
  editable: boolean;
  confirmed: boolean;
  busy: boolean;
  blocked?: boolean;
  saving?: boolean;
  idleHint?: string;
  blockedHint?: string;
  onConfirm: () => void;
}) {
  return (
    <div className="data-workbench-footer">
      <Typography.Text type="secondary" className="data-workbench-footer-hint">
        {saving
          ? '正在自动保存修改…'
          : blocked
            ? blockedHint
            : confirmed
              ? '本次数据已完成核对'
              : idleHint}
      </Typography.Text>
      <Button
        type="primary"
        disabled={!editable || confirmed || blocked || saving}
        loading={busy}
        onClick={onConfirm}
      >
        完成数据核对
      </Button>
    </div>
  );
}
