import { FileTextOutlined, RightOutlined } from '@ant-design/icons';
import { Empty, Tag, Typography } from 'antd';
import type { ReactNode } from 'react';

import type { DocumentStructure, DocumentStructureNode } from './types';

interface Props {
  structure?: DocumentStructure;
  selectedNodeId?: string;
  onSelect: (node: DocumentStructureNode) => void;
  children?: ReactNode;
}

export function DocumentOutlinePanel({ structure, selectedNodeId, onSelect, children }: Props) {
  const nodes = (structure?.nodes ?? []).filter(
    (node) => node.type === 'DOCUMENT_TITLE' || node.type === 'HEADING',
  );

  return (
    <aside className="document-outline-panel" aria-label="文档目录">
      <div className="side-panel-heading">
        <Typography.Text strong>文档目录</Typography.Text>
        <Typography.Text type="secondary">点击章节定位到正文</Typography.Text>
      </div>
      <div className="document-outline-summary">
        <Tag icon={<FileTextOutlined />}>{structure?.nodeCount ?? 0} 个结构节点</Tag>
        <Tag>{structure?.headingCount ?? nodes.filter((node) => node.type === 'HEADING').length} 个章节</Tag>
      </div>
      {children}
      {nodes.length ? (
        <div className="document-outline-list">
          {nodes.map((node) => (
            <button
              className={`document-outline-item${selectedNodeId === node.nodeId ? ' is-selected' : ''}`}
              key={node.nodeId}
              type="button"
              style={{ paddingLeft: `${12 + Math.min(node.level ?? 0, 6) * 16}px` }}
              onClick={() => onSelect(node)}
              title={node.title || node.text}
            >
              <RightOutlined className="document-outline-arrow" />
              <span>
                <small className="document-outline-kind">{outlineKindLabel(node.type)}</small>
                {node.title || node.text || (node.type === 'TABLE' ? '表格' : node.type === 'PAGE_BREAK' ? '分页' : '未命名内容')}
              </span>
            </button>
          ))}
        </div>
      ) : (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂未解析出章节" />
      )}
    </aside>
  );
}

function outlineKindLabel(type: DocumentStructureNode['type']) {
  switch (type) {
    case 'DOCUMENT_TITLE': return '标题';
    case 'HEADING': return '章节';
    default: return '内容';
  }
}
