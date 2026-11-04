import { Alert, App, Button, Descriptions, Form, Input, Modal, Select, Space, Spin, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { bibliographyApi, bibliographyStatus, type Bibliography } from '@/services/knowledge/bibliography-api';

const labels: Record<string, string> = {
  title: '题名', authors: '作者及机构', doi: 'DOI', venue: '期刊或会议', publication_date: '出版／公开日期',
  publication_year: '出版年份', volume: '卷', issue: '期', pages: '页码', abstract: '摘要', topics: '主题', source_url: '来源链接',
  patent_number: '公开号', application_number: '申请号', applicants: '申请人', inventors: '发明人', application_date: '申请日', ipc: 'IPC', cpc: 'CPC',
};
function display(value: unknown): string {
  if (Array.isArray(value)) return value.map((item: unknown) => {
    if (typeof item === 'string') return item;
    if (!item || typeof item !== 'object') return '';
    const author = item as { name?: string; institutions?: string[]; given?: string; family?: string };
    return `${author.name || [author.given, author.family].filter(Boolean).join(' ')}${author.institutions?.length ? `（${author.institutions.join('、')}）` : ''}`;
  }).filter(Boolean).join('；');
  return typeof value === 'string' || typeof value === 'number' ? String(value) : '';
}
export function BibliographyPanel({ documentId, versionId, canUpdate = false }: { documentId: string; versionId: string; canUpdate?: boolean }) {
  const { message } = App.useApp();
  const [value, setValue] = useState<Bibliography>(); const [error, setError] = useState<string>();
  const [editing, setEditing] = useState(false); const [saving, setSaving] = useState(false); const [form] = Form.useForm();
  const load = useCallback(async () => {
    setError(undefined);
    try { setValue(await bibliographyApi.get(documentId, versionId)); }
    catch (reason) { setError(reason instanceof Error ? reason.message : '书目信息加载失败'); }
  }, [documentId, versionId]);
  useEffect(() => { setValue(undefined); void load(); }, [load]);
  useEffect(() => {
    if (!value || !['PENDING', 'WAITING_PROVIDER'].includes(value.status) || error || editing) return;
    const timer = window.setTimeout(() => void load(), 10000);
    return () => window.clearTimeout(timer);
  }, [value, error, editing, load]);
  const edit = () => {
    if (!value) return;
    form.resetFields();
    form.setFieldsValue({ documentType: value.documentType, ...Object.fromEntries(Object.entries(value.accepted).map(([key, item]) => [key, display(item)])) });
    setEditing(true);
  };
  const save = async () => {
    if (!value) return;
    const fields = await form.validateFields(); const changed: Record<string, unknown> = {};
    for (const key of Object.keys(labels)) {
      const before = display(value.accepted[key]); const after = (fields[key] || '').trim();
      if (after !== before) changed[key] = after ? key === 'publication_year' ? Number(after) : after : null;
    }
    setSaving(true);
    try {
      setValue(await bibliographyApi.confirm(documentId, versionId, value.revision, fields.documentType, changed));
      setEditing(false); void message.success('书目信息已更新');
    } catch (reason) { void message.error(reason instanceof Error ? reason.message : '保存失败，请刷新后再试'); }
    finally { setSaving(false); }
  };
  const items = value ? Object.entries(labels).filter(([key]) => display(value.accepted[key])).map(([key, label]) => ({
    key, label, children: <><span style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{display(value.accepted[key])}</span>{value.provenance[key]?.anchor?.page && <Typography.Text type="secondary"> · 原文第 {value.provenance[key].anchor?.page} 页</Typography.Text>}</>,
  })) : [];
  return <details className="knowledge-document-metadata">
    <summary>书目信息</summary>
    {error ? <Alert type="error" message={error} action={<Button onClick={() => void load()}>重新加载</Button>} /> : !value ? <Spin /> : <>
      {canUpdate && <Space style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}><Button aria-label="编辑书目信息" onClick={edit}>编辑</Button></Space>}
      {items.length > 0 && <Descriptions column={{ xs: 1, md: 2 }} items={items} />}
      {value.status !== 'ACCEPTED' && <Typography.Paragraph type="secondary">{bibliographyStatus[value.status] || '部分书目信息暂未补全'}</Typography.Paragraph>}
      {value.status === 'ACCEPTED' && !items.length && <Typography.Paragraph type="secondary">暂无书目信息。</Typography.Paragraph>}
      <Modal open={editing} title="编辑书目信息" width={760} onCancel={() => setEditing(false)} onOk={() => void save()} confirmLoading={saving} okText="保存" cancelText="取消" destroyOnHidden>
        <Form form={form} layout="vertical">
          <Form.Item name="documentType" label="资料类型" rules={[{ required: true }]}><Select options={[{ value: 'UNKNOWN', label: '其他资料' }, { value: 'PAPER', label: '公开论文' }, { value: 'PATENT', label: '专利' }, { value: 'INTERNAL', label: '客户或内部资料' }]} /></Form.Item>
          {Object.entries(labels).map(([key, label]) => <Form.Item key={key} name={key} label={label}>{key === 'abstract' ? <Input.TextArea rows={4} maxLength={10000} /> : <Input maxLength={key === 'authors' ? 5000 : 1500} />}</Form.Item>)}
        </Form>
      </Modal>
    </>}
  </details>;
}
