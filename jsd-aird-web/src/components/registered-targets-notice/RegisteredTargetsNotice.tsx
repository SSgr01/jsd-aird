import { DownOutlined, UpOutlined } from '@ant-design/icons'
import { Alert, Button, Space, Tag } from 'antd'
import { useState } from 'react'
import './registered-targets-notice.css'

export interface RegisteredTargetNoticeItem {
  targetId?: string
  name: string
}
interface RegisteredTargetsNoticeProps {
  targets: RegisteredTargetNoticeItem[]
  /** Keep the copy short when the notice is embedded next to a selector. */
  compact?: boolean
}

/**
 * A quiet, on-demand summary for registered targets without enough data.
 * The target catalogue remains discoverable, but it no longer dominates
 * every assistant page with a repeated wall of disabled target names.
 */
export function RegisteredTargetsNotice({ targets, compact = false }: RegisteredTargetsNoticeProps) {
  const [expanded, setExpanded] = useState(false)
  if (!targets.length) return null

  return (
    <Alert
      className="registered-targets-notice"
      type="info"
      showIcon
      message={compact ? `另有 ${targets.length} 个目标已登记，暂缺可用数据` : `还有 ${targets.length} 个目标已登记，但当前数据不足`}
      description={expanded ? (
        <Space className="registered-targets-list" wrap size={[6, 6]}>
          {targets.map((target) => <Tag key={target.targetId ?? target.name}>{target.name}</Tag>)}
        </Space>
      ) : '不会影响其他已有数据的目标；数据积累后会自动出现在可用目标中。'}
      action={(
        <Button
          type="link"
          size="small"
          className="registered-targets-toggle"
          icon={expanded ? <UpOutlined /> : <DownOutlined />}
          onClick={() => setExpanded((value) => !value)}
        >
          {expanded ? '收起' : '查看目标'}
        </Button>
      )}
    />
  )
}
