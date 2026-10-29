import { App, Modal, Select, Space, Tag, Typography } from 'antd';
import { useMemo, useState } from 'react';
import { knowledgeApi, type KnowledgeDocument } from '@/services/knowledge';

export function KnowledgeBatchTagsModal({ documents, tagOptions, onCancel, onSaved }: {
  documents: KnowledgeDocument[]; tagOptions: string[]; onCancel: () => void; onSaved: () => void;
}) {
  const { message } = App.useApp();
  const [add, setAdd] = useState<string[]>([]);
  const [remove, setRemove] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const existingTags = useMemo(() => {
    const counts = new Map<string, number>();
    documents.forEach((item) => new Set(item.tags || []).forEach((tag) => counts.set(tag, (counts.get(tag) || 0) + 1)));
    return [...counts.entries()];
  }, [documents]);
  const save = async () => {
    setSaving(true);
    try {
      const results = await knowledgeApi.batchTags(documents.map(({id,revision}) => ({id,revision})), add, remove);
      const failures = results.filter((item) => !item.success);
      if (failures.length) {
        void message.warning(`已更新 ${results.length - failures.length} 个文档，${failures.length} 个失败：${failures[0]?.message || '请检查权限'}`);
      } else void message.success(`已更新 ${results.length} 个文档的标签`);
      onSaved();
    } catch (error) { void message.error(error instanceof Error ? error.message : '标签保存失败'); }
    finally { setSaving(false); }
  };
  return <Modal open title={`批量编辑标签 · ${documents.length} 个文档`} okText="应用修改" cancelText="取消" confirmLoading={saving}
    okButtonProps={{ disabled: !add.length && !remove.length }} onCancel={onCancel} onOk={() => void save()}>
    <Typography.Paragraph>现有标签 <Typography.Text type="secondary">点击 × 从所选文档移除；括号内为使用该标签的文档数。</Typography.Text></Typography.Paragraph>
    <Space wrap>{existingTags.filter(([tag]) => !remove.includes(tag)).map(([tag, count]) => <Tag key={tag} closable
      onClose={(event) => { event.preventDefault(); setRemove((values) => [...values, tag]); setAdd((values) => values.filter((value) => value !== tag)); }}>{tag}（{count}）</Tag>)}</Space>
    {!existingTags.length && <Typography.Paragraph type="secondary">所选文档暂无标签</Typography.Paragraph>}
    {!!remove.length && <><Typography.Paragraph style={{ marginTop: 16 }}>待移除 <Typography.Text type="secondary">点击 × 撤销移除。</Typography.Text></Typography.Paragraph><Space wrap>{remove.map((tag) => <Tag color="red" key={tag} closable onClose={(event) => { event.preventDefault(); setRemove((values) => values.filter((value) => value !== tag)); }}>{tag}</Tag>)}</Space></>}
    <Typography.Paragraph style={{ marginTop: 16 }}>添加到所选文档</Typography.Paragraph>
    <Select style={{ width: '100%' }} mode="tags" allowClear value={add} tokenSeparators={[',', '，']} placeholder="选择或输入新标签"
      options={tagOptions.filter((tag) => !remove.includes(tag)).map((tag) => ({ value: tag, label: tag }))}
      onChange={(values) => { setAdd(values); setRemove((current) => current.filter((tag) => !values.includes(tag))); }} />
  </Modal>;
}
