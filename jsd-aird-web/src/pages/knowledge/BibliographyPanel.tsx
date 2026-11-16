import { Alert, App, Button, Descriptions, Form, Input, Select, Space, Spin, Typography } from 'antd';
import { FormModal } from '@/components/form-dialog/FormModal';
import { useCallback, useEffect, useState } from 'react';
import { bibliographyApi, bibliographyStatus, type Bibliography } from '@/services/knowledge/bibliography-api';

const labels: Record<string, string> = {
  document_kind: '资料性质（模型建议）', organization: '编写单位', document_date: '资料日期', document_number: '资料编号',
  version: '版本',
  title: '题名', authors: '作者及机构', doi: 'DOI', venue: '期刊或会议', publication_date: '出版／公开日期',
  publication_year: '出版年份', volume: '卷', issue: '期', pages: '页码', abstract: '摘要', topics: '主题', source_url: '来源链接',
  patent_number: '公开号', application_number: '申请号', applicants: '申请人', inventors: '发明人', application_date: '申请日', ipc: 'IPC', cpc: 'CPC',
};
const sourceFields = new Set(['document_kind', 'organization', 'document_date', 'document_number', 'version']);
const commonFields = new Set(['document_kind', 'title', 'authors', 'organization', 'document_date', 'document_number', 'version', 'abstract', 'source_url']);
const sourceStatus: Record<string, string> = { PENDING: '正在补全资料信息', WAITING_PROVIDER: '资料信息处理中，等待服务返回', MISSING_PARSE: '缺少解析结果', FAILED: '资料信息补全失败', STALE: '文件或解析结果已变化' };
type ExtraField = { label: string; value: string };
function extraFields(value: unknown): ExtraField[] {
  return Array.isArray(value) ? value.filter((item): item is ExtraField => !!item && typeof item === 'object' && typeof item.label === 'string' && typeof item.value === 'string') : [];
}
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
    catch (reason) { setError(reason instanceof Error ? reason.message : '资料信息加载失败'); }
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
    form.setFieldsValue({ documentType: value.documentType, ...Object.fromEntries(Object.entries(value.accepted).map(([key, item]) => [key, display(item)])), extra_fields: extraFields(value.accepted.extra_fields) });
    setEditing(true);
  };
  const save = async () => {
    if (!value) return;
    const fields = await form.validateFields(); const changed: Record<string, unknown> = {};
    for (const key of Object.keys(labels)) {
      const before = display(value.accepted[key]); const after = (fields[key] || '').trim();
      if (after !== before) changed[key] = after ? key === 'publication_year' ? Number(after) : after : null;
    }
    const extra = extraFields(fields.extra_fields).map((item) => ({ label: item.label.trim(), value: item.value.trim() }));
    if (JSON.stringify(extra) !== JSON.stringify(extraFields(value.accepted.extra_fields))) changed.extra_fields = extra;
    setSaving(true);
    try {
      setValue(await bibliographyApi.confirm(documentId, versionId, value.revision, fields.documentType, changed));
      setEditing(false); void message.success('资料信息已更新');
    } catch (reason) { void message.error(reason instanceof Error ? reason.message : '保存失败，请刷新后再试'); }
    finally { setSaving(false); }
  };
  const items = value ? Object.entries(labels).filter(([key]) => display(value.accepted[key])).map(([key, label]) => ({
    key, label, children: <><span style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{display(value.accepted[key])}</span>{value.provenance[key]?.anchor?.page && <Typography.Text type="secondary"> · 原文第 {value.provenance[key].anchor?.page} 页</Typography.Text>}</>,
  })) : [];
  const heading = value && !['PAPER', 'PATENT'].includes(value.documentType) ? '资料信息' : '书目信息';
  const extraItems = extraFields(value?.accepted.extra_fields).map((item, index) => ({ key: `extra-${index}`, label: item.label, children: <span style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{item.value}</span> }));
  const isBibliographic = !!value && ['PAPER', 'PATENT'].includes(value.documentType);
  return <details className="knowledge-document-metadata">
    <summary>{heading}</summary>
    {error ? <Alert type="error" message={error} action={<Button onClick={() => void load()}>重新加载</Button>} /> : !value ? <Spin /> : <>
      {canUpdate && <Space style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}><Button aria-label={`编辑${heading}`} onClick={edit}>编辑</Button></Space>}
      {items.length + extraItems.length > 0 && <Descriptions column={{ xs: 1, md: 2 }} items={[...items, ...extraItems]} />}
      {value.status !== 'ACCEPTED' && <Typography.Paragraph type="secondary">{value.provenance._model_extraction?.status === 'SUCCEEDED'
        ? '已从原文提取资料信息，保留原文依据；不代表已通过外部书目核验'
        : ['FAILED', 'UNKNOWN', 'INVALID_RESPONSE'].includes(value.provenance._model_extraction?.status || '') ? '原文信息提取未完成，已保留已有信息。'
        : isBibliographic ? bibliographyStatus[value.status] || '部分书目信息暂未补全' : sourceStatus[value.status] || '仅显示已提取的信息；原文未提供的信息留空。'}</Typography.Paragraph>}
      {value.status === 'ACCEPTED' && !items.length && !extraItems.length && <Typography.Paragraph type="secondary">暂无{heading}。</Typography.Paragraph>}
      <FormModal form={form} open={editing} title={`编辑${heading}`} width={760} onCancel={() => setEditing(false)} onOk={() => save()} confirmLoading={saving} okText="保存" cancelText="取消" destroyOnHidden>
        <Form form={form} layout="vertical">
          <Form.Item name="documentType" label="书目处理方式" rules={[{ required: true }]}><Select options={[{ value: 'UNKNOWN', label: '不确定（不查询外部）' }, { value: 'PAPER', label: '公开论文书目核验' }, { value: 'PATENT', label: '专利字段' }, { value: 'INTERNAL', label: '本地资料信息' }]} /></Form.Item>
          {Object.entries(labels).filter(([key]) => display(value?.accepted[key]) || (isBibliographic ? !sourceFields.has(key) : commonFields.has(key))).map(([key, label]) => <Form.Item key={key} name={key} label={label}>{key === 'abstract' ? <Input.TextArea rows={4} maxLength={10000} /> : <Input maxLength={key === 'authors' ? 5000 : 1500} />}</Form.Item>)}
          <Typography.Text strong>扩展信息</Typography.Text>
          <Typography.Paragraph type="secondary">按资料实际内容填写名称和值，不限定资料类型。</Typography.Paragraph>
          <Form.List name="extra_fields">{(fields, { add, remove }) => <>
            {fields.map((field, index) => <Space key={field.key} align="start" style={{ display: 'flex' }}>
              <Form.Item name={[field.name, 'label']} rules={[{ required: true, whitespace: true, message: '请填写信息名称' }, { max: 64 }]}><Input aria-label={`扩展信息名称 ${index + 1}`} placeholder="信息名称" maxLength={64} /></Form.Item>
              <Form.Item name={[field.name, 'value']} rules={[{ required: true, whitespace: true, message: '请填写信息内容' }, { max: 1000 }]}><Input.TextArea aria-label={`扩展信息内容 ${index + 1}`} placeholder="原文信息" autoSize={{ minRows: 1, maxRows: 4 }} maxLength={1000} /></Form.Item>
              <Button onClick={() => remove(field.name)} aria-label={`删除扩展信息 ${index + 1}`}>删除</Button>
            </Space>)}
            {fields.length < 20 && <Button onClick={() => add({ label: '', value: '' })}>添加信息</Button>}
          </>}</Form.List>
        </Form>
      </FormModal>
    </>}
  </details>;
}
