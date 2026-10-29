import { Button } from 'antd';
import { useState } from 'react';

import type { FieldDefinitionReviewItem } from './field-definition-review';

export function FieldDefinitionReview({
  items,
  busy,
  onEdit,
  onConfirm,
}: {
  items: FieldDefinitionReviewItem[];
  busy?: boolean;
  onEdit?: (item: FieldDefinitionReviewItem) => void;
  onConfirm?: (item: FieldDefinitionReviewItem) => Promise<void>;
}) {
  const [confirming, setConfirming] = useState<string>();
  if (!items.length) return null;
  return (
    <section className="data-field-definition-review" aria-label="待确认字段定义">
      <header className="data-field-definition-review-head">
        <strong>{items.length} 个新增字段需要确认</strong>
        <span>字段值已通过。请核对类型和单位，有误可直接修改。</span>
      </header>
      <div className="data-field-definition-review-list">
        {items.map((item) => (
          <article className="data-field-definition-review-row" key={item.fieldId}>
            <div className="data-field-definition-review-identity">
              <strong>{item.name}</strong>
              <span>{[item.sheetName, item.address].filter(Boolean).join(' · ') || '本次记录'}</span>
            </div>
            <div className="data-field-definition-review-facts">
              <span><small>类型</small>{fieldTypeLabel(item.valueType)}</span>
              <span><small>单位</small>{item.unit?.trim() || '无单位'}</span>
            </div>
            <div className="data-field-definition-review-actions">
              <Button type="text" size="small" disabled={busy || Boolean(confirming)} onClick={() => onEdit?.(item)}>
                修改
              </Button>
              <Button
                type="primary"
                size="small"
                loading={confirming === item.fieldId}
                disabled={busy || Boolean(confirming) || !onConfirm}
                onClick={() => {
                  setConfirming(item.fieldId);
                  void onConfirm?.(item).catch(() => undefined).finally(() => setConfirming(undefined));
                }}
              >
                确认无误
              </Button>
            </div>
          </article>
        ))}
      </div>
    </section>
  );
}

function fieldTypeLabel(valueType: string) {
  const labels: Record<string, string> = {
    string: '文本', text: '文本', number: '数值', decimal: '数值', integer: '整数',
    boolean: '是/否', date: '日期', datetime: '日期时间',
  };
  return labels[valueType.toLowerCase()] ?? valueType;
}
