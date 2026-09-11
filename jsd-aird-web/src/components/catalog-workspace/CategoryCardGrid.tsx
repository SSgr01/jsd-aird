import { CheckOutlined, DeleteOutlined, EditOutlined, FolderAddOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Card, Empty, Space, Typography } from 'antd';
import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';

export interface CatalogCategoryCard {
  id: string;
  name: string;
  count: number;
  description?: string;
  icon?: ReactNode;
  tone?: 'blue' | 'green' | 'violet' | 'orange' | 'teal';
  editable?: boolean;
  allowedActions?: string[];
}

interface CategoryCardGridProps {
  categories: CatalogCategoryCard[];
  activeId?: string;
  addLabel?: string;
  countLabel?: string;
  onSelect: (id: string) => void;
  onCreate?: () => void;
  onRename?: (category: CatalogCategoryCard) => void;
  onDelete?: (category: CatalogCategoryCard) => void;
}

export function CategoryCardGrid({ categories, activeId, addLabel = '新增分类', countLabel = '条记录', onSelect, onCreate, onRename, onDelete }: CategoryCardGridProps) {
  const gridRef = useRef<HTMLDivElement>(null);
  const categorySignature = categories.map((category) => category.id).join('|');

  // When a second-level category set is loaded or switched on a narrow
  // viewport, start at the first real category. Without this reset, the
  // browser can retain the previous horizontal scroll position and land on
  // the trailing “新增分类” card.
  useEffect(() => {
    if (gridRef.current) gridRef.current.scrollLeft = 0;
  }, [categorySignature]);

  return (
    <div ref={gridRef} className="catalog-category-grid" aria-label="分类列表">
      {categories.map((category) => (
        <Card
          key={category.id}
          className={`catalog-category-card${activeId === category.id ? ' is-active' : ''}`}
          hoverable
        >
          {activeId === category.id && <span className="catalog-category-active-mark" aria-hidden="true"><CheckOutlined /></span>}
          <div
            className="catalog-category-select"
            onClick={() => onSelect(category.id)}
            role="button"
            tabIndex={0}
            aria-pressed={activeId === category.id}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                onSelect(category.id);
              }
            }}
          >
            <div className={`catalog-category-icon tone-${category.tone || 'blue'}`}>{category.icon || <FolderAddOutlined />}</div>
            <div className="catalog-category-copy">
              <Typography.Title level={4}>{category.name}</Typography.Title>
              {category.description && <Typography.Paragraph ellipsis={{ rows: 2 }}>{category.description}</Typography.Paragraph>}
              <Typography.Text strong>{category.count}</Typography.Text><Typography.Text type="secondary"> {countLabel}</Typography.Text>
            </div>
          </div>
          {category.editable && (onRename || (onDelete && category.allowedActions?.includes('DELETE'))) && (
            <Space className="catalog-category-actions" size={2}>
              {onRename && <Button type="text" size="small" icon={<EditOutlined />} aria-label={`重命名${category.name}`} onClick={() => onRename(category)} />}
              {onDelete && category.allowedActions?.includes('DELETE') && <Button type="text" danger size="small" icon={<DeleteOutlined />} aria-label={`删除${category.name}`} onClick={() => onDelete(category)} />}
            </Space>
          )}
        </Card>
      ))}
      {onCreate && (
        <Card className="catalog-category-card catalog-category-add" hoverable onClick={onCreate} role="button" tabIndex={0} onKeyDown={(event) => {
          if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onCreate(); }
        }}>
          <PlusOutlined className="catalog-category-add-icon" />
          <Typography.Title level={4}>{addLabel}</Typography.Title>
          <Typography.Text type="secondary">创建自定义分类</Typography.Text>
        </Card>
      )}
      {!categories.length && !onCreate && <Empty description="暂无分类" />}
    </div>
  );
}
