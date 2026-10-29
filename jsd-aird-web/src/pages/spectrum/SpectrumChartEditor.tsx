import { App, Form, Input, Modal, Select, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { getExperiment, listExperiments, type ExperimentSummary } from '@/services/experiments/experiment-api';
import { spectrumApi, type SpectrumCategory, type SpectrumChart } from '@/services/spectrum';

type ChartValues = {
  title: string;
  categoryId: string;
  sampleName?: string;
  batchNo?: string;
  testConditions?: string;
  metadata: Record<string, unknown>;
};

export function SpectrumChartEditor({ item, categories, onCancel, onSaved }: {
  item: SpectrumChart; categories: SpectrumCategory[]; onCancel: () => void; onSaved: () => void;
}) {
  const { message } = App.useApp();
  const [form] = Form.useForm<ChartValues>();
  const categoryId = Form.useWatch('categoryId', form) || item.categoryId;
  const category = categories.find((value) => value.id === categoryId);
  const [experiments, setExperiments] = useState<ExperimentSummary[]>([]);
  const [loadingExperiments, setLoadingExperiments] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let active = true;
    setLoadingExperiments(true);
    void (async () => {
      const result = await listExperiments({ page: 1, size: 100 });
      if (active) setExperiments(result.items);
      const selectedId = item.metadata.experimentId;
      if (typeof selectedId === 'string' && selectedId && !result.items.some((value) => value.id === selectedId)) {
        const selected = await getExperiment(selectedId);
        if (active) setExperiments([selected.summary, ...result.items]);
      }
    })().catch((error: unknown) => {
      if (active) void message.warning(error instanceof Error ? error.message : '实验列表加载失败');
    }).finally(() => { if (active) setLoadingExperiments(false); });
    return () => { active = false; };
  }, [item.metadata.experimentId, message]);

  const save = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await spectrumApi.updateChart(item.id, {
        revision: item.revision,
        title: values.title.trim(), categoryId: values.categoryId,
        sampleName: values.sampleName?.trim() || '', batchNo: values.batchNo?.trim() || '',
        testConditions: values.testConditions?.trim() || '',
        metadata: { ...item.metadata, ...values.metadata },
      });
      void message.success('图谱信息已保存');
      onSaved();
    } catch (error) { void message.error(error instanceof Error ? error.message : '图谱信息保存失败'); }
    finally { setSaving(false); }
  };

  return <Modal open title="编辑图谱" width={720} okText="保存" cancelText="取消" confirmLoading={saving}
    onCancel={onCancel} onOk={() => { void save().catch(() => undefined); }}>
    <Form form={form} layout="vertical" initialValues={{
      title: item.title, categoryId: item.categoryId, sampleName: item.sampleName,
      batchNo: item.batchNo, testConditions: item.testConditions, metadata: { ...item.metadata },
    }}>
      <Form.Item name="title" label="图谱名称" rules={[{ required: true, whitespace: true, message: '请输入图谱名称' }]}><Input maxLength={260} /></Form.Item>
      <Form.Item name="categoryId" label="图谱分类" rules={[{ required: true, message: '请选择图谱分类' }]}><Select showSearch optionFilterProp="label" options={categories.map((value) => ({ value: value.id, label: value.name }))} /></Form.Item>
      <Form.Item name="sampleName" label="样品名称"><Input maxLength={260} placeholder="可选，如竞品 A、单峰参考 01" /></Form.Item>
      <Form.Item name="batchNo" label="批号"><Input maxLength={160} placeholder="可选" /></Form.Item>
      <Form.Item name={['metadata', 'experimentId']} label="关联 ELN 实验"><Select allowClear showSearch loading={loadingExperiments} optionFilterProp="label" placeholder="可选，选择实验后形成稳定关联" options={experiments.map((value) => ({ value: value.id, label: `${value.experimentNo} · ${value.title}` }))} /></Form.Item>
      <Form.Item name="testConditions" label="测试条件"><Input.TextArea rows={2} maxLength={10000} placeholder="仪器、范围、溶剂、倍率等，已知则填写" /></Form.Item>
      {category?.fields.map((field) => <Form.Item key={field.key} name={['metadata', field.key]} label={field.label}><Input placeholder="可选" /></Form.Item>)}
      {category?.analysisHint && <Typography.Paragraph type="secondary">当前分类分析提示：{category.analysisHint}</Typography.Paragraph>}
    </Form>
  </Modal>;
}
