import { Dropdown } from 'antd';
import { ConversationIconButton } from '@/components/ai-conversation-workspace/ConversationIconButton';
import type { ConversationEntry, QueueChange } from '@/services/agents/agent-api';
import { useQueueSorting } from './useQueueSorting';

interface Props {
  items: ConversationEntry[];
  paused?: boolean;
  busy: boolean;
  canSteer: boolean;
  canRestore?: boolean;
  onRestore?: (item: ConversationEntry) => Promise<void>;
  onChange: (change: QueueChange) => Promise<boolean>;
}

export function QueuedMessages({
  items,
  paused,
  busy,
  canSteer,
  canRestore = true,
  onRestore,
  onChange,
}: Props) {
  const sorting = useQueueSorting(items, busy, onChange);
  const locked = busy || sorting.saving;
  const actionsLocked = locked || Boolean(sorting.draggingId);
  if (!items.length) return null;
  return (
    <section
      className={`harness-queue${sorting.draggingId ? ' is-sorting' : ''}`}
      aria-label="待处理消息"
    >
      <div className="harness-queue-heading">
        <span>待发送 · {items.length}</span>
        <span className="harness-queue-hint" role="status">
          {sorting.saving
            ? '正在保存顺序…'
            : paused
              ? '已暂停，输入“继续”后接着处理'
              : '当前任务完成后依次处理'}
        </span>
      </div>
      <ol ref={sorting.listRef}>
        {sorting.displayed.map((item, index) => (
          <li
            key={item.id}
            data-queue-id={item.id}
            className={sorting.draggingId === item.id ? 'harness-queue-dragging' : undefined}
          >
            <ConversationIconButton
              className="harness-queue-handle"
              size="small"
              name="grip"
              disabled={locked || items.length < 2}
              label={`调整顺序：${item.text}`}
              tooltip={false}
              title="拖动排序"
              aria-description="也可用上下方向键或更多菜单调整顺序"
              onPointerDown={(event) => sorting.startDrag(event, item.id)}
              onPointerMove={sorting.updateDrag}
              onPointerUp={sorting.endDrag}
              onPointerCancel={sorting.cancelDrag}
              onLostPointerCapture={sorting.cancelDrag}
              onKeyDown={(event) => {
                if (event.key === 'Escape') sorting.cancelDrag();
                if (actionsLocked || !['ArrowUp', 'ArrowDown'].includes(event.key)) return;
                event.preventDefault();
                const position = index + (event.key === 'ArrowUp' ? -1 : 1);
                if (position >= 0 && position < items.length) void sorting.move(item.id, position);
              }}
            />
            <span className="harness-queue-text" title={item.text}>
              {item.text}
            </span>
            <div className="harness-queue-actions">
              <ConversationIconButton
                size="small"
                name="edit"
                label={`编辑消息：${item.text}`}
                tooltip={
                  canRestore ? '放回输入框编辑' : '输入框已有内容，请先发送或清空，再编辑这条消息'
                }
                disabled={actionsLocked || !canRestore || !onRestore}
                onClick={() => void onRestore?.(item)}
              />
              {canSteer && (
                <ConversationIconButton
                  size="small"
                  name="send"
                  label={`立即补充：${item.text}`}
                  tooltip="立即补充到当前任务，当前步骤结束后接收"
                  disabled={actionsLocked}
                  onClick={() => void onChange({ action: 'promote', message_id: item.id })}
                />
              )}
              <ConversationIconButton
                size="small"
                name="close"
                label={`取消发送：${item.text}`}
                tooltip="取消发送"
                disabled={actionsLocked}
                onClick={() => void onChange({ action: 'delete', message_id: item.id })}
              />
              {items.length > 1 && (
                <Dropdown
                  trigger={['click']}
                  disabled={actionsLocked}
                  menu={{
                    items: [
                      { key: 'up', label: '上移', disabled: index === 0 },
                      { key: 'down', label: '下移', disabled: index === items.length - 1 },
                    ],
                    onClick: ({ key }) => {
                      void sorting.move(item.id, index + (key === 'up' ? -1 : 1));
                    },
                  }}
                >
                  <span className="harness-queue-more">
                    <ConversationIconButton
                      size="small"
                      name="more"
                      label={`更多操作：${item.text}`}
                      tooltip="更多操作"
                      disabled={actionsLocked}
                    />
                  </span>
                </Dropdown>
              )}
            </div>
          </li>
        ))}
      </ol>
    </section>
  );
}
