import { App, Form, Input, Modal, Select } from 'antd';
import { useEffect, useState } from 'react';
import { usePermission } from '@/components/auth/usePermission';
import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';
import { knowledgeApi, type KnowledgeCategory, type KnowledgeDocument } from '@/services/knowledge';

const knowledgeVisibilityOptions = ['研发部可见', '全员可见', '项目组可见'].map((value) => ({ value, label: value }));

type MetadataValues = {
  title: string;
  libraryScope: 'INTERNAL' | 'EXTERNAL';
  categoryId: string;
  tags: string[];
  description: string;
  visibility: string;
  projectRelations: ProjectRelationTarget[];
};

function ProjectRelationsField({ value = [], onChange }: {
  value?: ProjectRelationTarget[]; onChange?: (value: ProjectRelationTarget[]) => void;
}) {
  return <ProjectRelationPicker value={value} onChange={(targets) => onChange?.(targets)} />;
}

export function KnowledgeDocumentEditor({ item, tagOptions, onCancel, onSaved }: {
  item: KnowledgeDocument; tagOptions: string[]; onCancel: () => void; onSaved: () => void;
}) {
  const { message } = App.useApp();
  const [form] = Form.useForm<MetadataValues>();
  const libraryScope = Form.useWatch('libraryScope', form) || item.libraryScope;
  const [categories, setCategories] = useState<KnowledgeCategory[]>([]);
  const [loadingCategories, setLoadingCategories] = useState(false);
  const [saving, setSaving] = useState(false);
  const canAssign = usePermission('project.assign');

  useEffect(() => {
    let active = true;
    setLoadingCategories(true);
    void knowledgeApi.categories(libraryScope).then((values) => { if (active) setCategories(values); })
      .catch((error: unknown) => { if (active) void message.error(error instanceof Error ? error.message : '分类加载失败'); })
      .finally(() => { if (active) setLoadingCategories(false); });
    return () => { active = false; };
  }, [libraryScope, message]);

  const save = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await knowledgeApi.updateMetadata(item.id, {
        title: values.title.trim(), libraryScope: values.libraryScope, categoryId: values.categoryId,
        tags: values.tags || [],
        sourceInfo: { ...item.sourceInfo, description: values.description?.trim() || '', visibility: values.visibility },
        projectRelations: canAssign ? values.projectRelations || [] : undefined,
      });
      void message.success('文档信息已保存');
      onSaved();
    } catch (error) { void message.error(error instanceof Error ? error.message : '文档信息保存失败'); }
    finally { setSaving(false); }
  };

  return <Modal open title="编辑知识文档" width={760} okText="保存" cancelText="取消" confirmLoading={saving}
    onCancel={onCancel} onOk={() => { void save().catch(() => undefined); }}>
    <Form form={form} layout="vertical" initialValues={{
      title: item.title, libraryScope: item.libraryScope, categoryId: item.categoryId,
      tags: item.tags || [], description: item.sourceInfo?.description || '',
      visibility: item.sourceInfo?.visibility || '研发部可见',
      projectRelations: (item.relatedProjects || []).map(({ projectId, stageId, taskId }) => ({ projectId, stageId, taskId })),
    }}>
      <Form.Item name="title" label="文件名称" rules={[{ required: true, whitespace: true, message: '请输入文件名称' }]}><Input maxLength={260} /></Form.Item>
      <Form.Item name="libraryScope" label="资料范围" rules={[{ required: true }]}><Select options={[{ value: 'INTERNAL', label: '内部资料' }, { value: 'EXTERNAL', label: '外部资料' }]} onChange={() => form.setFieldValue('categoryId', undefined)} /></Form.Item>
      <Form.Item name="categoryId" label="分类" rules={[{ required: true, message: '请选择分类' }]}><Select loading={loadingCategories} showSearch optionFilterProp="label" options={categories.map((value) => ({ value: value.id, label: value.name }))} /></Form.Item>
      <Form.Item name="tags" label="标签"><Select mode="tags" allowClear tokenSeparators={[',', '，']} placeholder="选择或输入标签，点击 × 移除" options={tagOptions.map((value) => ({ value, label: value }))} /></Form.Item>
      <Form.Item name="description" label="来源信息"><Input.TextArea rows={3} maxLength={2000} placeholder="资料来源、提供方或获取背景" /></Form.Item>
      {canAssign && <Form.Item name="projectRelations" label="关联项目 / 阶段 / 任务"><ProjectRelationsField /></Form.Item>}
      <Form.Item name="visibility" label="权限可见" rules={[{ required: true }]}><Select options={knowledgeVisibilityOptions} /></Form.Item>
    </Form>
  </Modal>;
}
