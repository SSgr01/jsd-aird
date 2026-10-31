import { ArrowUpOutlined, HolderOutlined, MoreOutlined, RollbackOutlined } from '@ant-design/icons';
import { Button, Dropdown, Input, Modal } from 'antd';
import { useRef, useState } from 'react';
import type { ConversationEntry, QueueChange } from '@/services/agents/agent-api';

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
  const [editing, setEditing] = useState<ConversationEntry>();
  const [text, setText] = useState('');
  const drag = useRef<{
    id: string;
    pointerId: number;
    startY: number;
    order: string;
    overId?: string;
  }>();
  const [dragging, setDragging] = useState<{ id: string; overId: string }>();
  const clearDrag = () => {
    drag.current = undefined;
    setDragging(undefined);
  };
  const order = JSON.stringify(items.map((item) => item.id));
  if (!items.length && !editing) return null;
  return (
    <section className="harness-queue" aria-label="待处理消息">
      <div className="harness-queue-heading">
        已排队 · {items.length}
        {paused ? ' · 已暂停' : ''}
      </div>
      {paused && (
        <p className="harness-muted">
          输入“继续”可接着当前任务，完成后再处理待办；也可以先编辑或移除待办。
        </p>
      )}
      <ol>
        {items.map((item, index) => (
          <li
            key={item.id}
            data-queue-id={item.id}
            className={[
              dragging?.id === item.id ? 'harness-queue-dragging' : '',
              dragging?.overId === item.id && dragging.id !== item.id
                ? 'harness-queue-drop-target'
                : '',
            ].join(' ')}
          >
            <Button
              className="harness-queue-handle"
              size="small"
              type="text"
              icon={<HolderOutlined />}
              disabled={busy || items.length < 2}
              aria-label={`拖动排序：${item.text}`}
              title="拖动排序，也可使用上下方向键调整顺序"
              onPointerDown={(event) => {
                if (busy || items.length < 2 || event.button !== 0 || !event.isPrimary) return;
                event.currentTarget.focus();
                event.currentTarget.setPointerCapture(event.pointerId);
                drag.current = {
                  id: item.id,
                  pointerId: event.pointerId,
                  startY: event.clientY,
                  order,
                };
              }}
              onPointerMove={(event) => {
                const current = drag.current;
                if (!current || current.pointerId !== event.pointerId) return;
                if (busy || current.order !== order) {
                  clearDrag();
                  return;
                }
                if (!current.overId && Math.abs(event.clientY - current.startY) < 5) return;
                const list = event.currentTarget.closest('ol');
                const row = document
                  .elementFromPoint(event.clientX, event.clientY)
                  ?.closest<HTMLElement>('[data-queue-id]');
                const overId = row && list?.contains(row) ? row.dataset.queueId : undefined;
                current.overId = overId;
                setDragging({ id: current.id, overId: overId || current.id });
              }}
              onPointerUp={(event) => {
                const current = drag.current;
                if (!current || current.pointerId !== event.pointerId) return;
                clearDrag();
                if (
                  busy ||
                  current.order !== order ||
                  !current.overId ||
                  current.id === current.overId
                )
                  return;
                const position = items.findIndex((entry) => entry.id === current.overId);
                if (position >= 0)
                  void onChange({ action: 'move', message_id: current.id, position });
              }}
              onPointerCancel={clearDrag}
              onLostPointerCapture={clearDrag}
              onKeyDown={(event) => {
                if (event.key === 'Escape') clearDrag();
                if (busy || drag.current || !['ArrowUp', 'ArrowDown'].includes(event.key)) return;
                event.preventDefault();
                const position = index + (event.key === 'ArrowUp' ? -1 : 1);
                if (position >= 0 && position < items.length)
                  void onChange({ action: 'move', message_id: item.id, position });
              }}
            />
            <span className="harness-queue-text" title={item.text}>
              {item.text}
            </span>
            <div className="harness-queue-actions">
              {onRestore && (
                <Button
                  size="small"
                  type="text"
                  icon={<RollbackOutlined />}
                  aria-label="回退编辑"
                  disabled={busy || !canRestore}
                  title={
                    canRestore
                      ? '撤回尚未执行的消息，放回输入框编辑'
                      : '请先发送或清空输入框草稿，再回退编辑'
                  }
                  onClick={() => void onRestore(item)}
                />
              )}
              {canSteer && (
                <Button
                  size="small"
                  type="text"
                  icon={<ArrowUpOutlined />}
                  aria-label="立即补充"
                  disabled={busy}
                  title="立即补充：下一安全执行边界生效"
                  onClick={() => void onChange({ action: 'promote', message_id: item.id })}
                />
              )}
              <Dropdown
                trigger={['click']}
                menu={{
                  items: [
                    { key: 'edit', label: '编辑' },
                    { key: 'up', label: '上移', disabled: index === 0 },
                    { key: 'down', label: '下移', disabled: index === items.length - 1 },
                    { key: 'delete', label: '移除待发送消息', danger: true },
                  ],
                  onClick: ({ key }) => {
                    if (key === 'edit') {
                      setEditing(item);
                      setText(item.text);
                    } else
                      void onChange({
                        action: key === 'delete' ? 'delete' : 'move',
                        message_id: item.id,
                        ...(key === 'delete' ? {} : { position: index + (key === 'up' ? -1 : 1) }),
                      });
                  },
                }}
              >
                <Button
                  size="small"
                  type="text"
                  icon={<MoreOutlined />}
                  disabled={busy}
                  aria-label={`管理待处理消息：${item.text}`}
                />
              </Dropdown>
            </div>
          </li>
        ))}
      </ol>
      <Modal
        title="编辑待处理消息"
        open={Boolean(editing)}
        onCancel={() => setEditing(undefined)}
        okText="保存"
        cancelText="取消"
        confirmLoading={busy}
        okButtonProps={{ disabled: !text.trim() }}
        onOk={() => {
          if (editing)
            void onChange({ action: 'edit', message_id: editing.id, text: text.trim() }).then(
              (ok) => {
                if (ok) setEditing(undefined);
              },
            );
        }}
      >
        <Input.TextArea
          aria-label="编辑待处理消息内容"
          value={text}
          maxLength={12000}
          autoSize={{ minRows: 3, maxRows: 8 }}
          onChange={(event) => setText(event.target.value)}
        />
      </Modal>
    </section>
  );
}
