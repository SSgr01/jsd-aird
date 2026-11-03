import { Alert, Spin, Typography } from 'antd';

/** Display only. Retry, stop and queue policy always belongs to the profile adapter. */
export function ConversationFeedback({
  kind,
  message,
}: {
  kind: 'loading' | 'restoring' | 'error';
  message: string;
}) {
  if (kind === 'error') return <Alert type="error" message={message} />;
  return (
    <span role="status" aria-label={message}>
      <Spin size="small" /> <Typography.Text type="secondary">{message}</Typography.Text>
    </span>
  );
}
