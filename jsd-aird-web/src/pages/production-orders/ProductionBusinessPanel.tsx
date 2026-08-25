import {
  CheckCircleOutlined,
  DeleteOutlined,
  LinkOutlined,
  PlusOutlined,
  RollbackOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { Alert, Button, Card, Col, Input, InputNumber, Row, Select, Space, Tag, Typography } from 'antd';
import { useRef } from 'react';

import { useAuthStore } from '@/stores/auth-store';

export type ProductionBusinessStatus = 'NOT_ORDERED' | 'ORDERED' | 'MASS_PRODUCED' | 'VOID';

export interface ProductionTaskRow {
  id: string;
  process: string;
  owner: string;
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
  plannedDate: string;
}

export interface ProductionMaterialRow {
  id: string;
  material: string;
  batch: string;
  theoretical: number;
  actual: number;
}

export interface ProductionInspectionRow {
  id: string;
  date: string;
  inspector: string;
  result: 'PASS' | 'FAIL' | 'PENDING';
  note: string;
}

export interface ProductionOutputRow {
  id: string;
  batch: string;
  quantity: number;
  packageSpec: string;
  packageDate: string;
}

export interface ProductionAuditEntry {
  id: string;
  action: string;
  detail: string;
  createdAt: string;
  operator: string;
}

export interface ProductionBusinessSnapshot {
  status: ProductionBusinessStatus;
  taskRows: ProductionTaskRow[];
  materialRows: ProductionMaterialRow[];
  inspectionRows: ProductionInspectionRow[];
  outputRows: ProductionOutputRow[];
  formula: { expression: string; ratio: number; tolerance: number };
  signatures: { operator: string; quality: string; reviewer: string; signedAt: string };
  attachments: Array<{ id: string; name: string; kind: 'MATERIAL_PHOTO' | 'PROCESS_ATTACHMENT' }>;
  links: {
    rawMaterialIssued: boolean;
    finishedGoodsStored: boolean;
    differencePosted: boolean;
    recipeReferenced: boolean;
  };
  audit: ProductionAuditEntry[];
}

const statusLabels: Record<ProductionBusinessStatus, string> = {
  NOT_ORDERED: '未下单',
  ORDERED: '已下单',
  MASS_PRODUCED: '已量产',
  VOID: '已作废',
};

const taskStatusLabels: Record<ProductionTaskRow['status'], string> = {
  PENDING: '待开始',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};

const allowedStatusTransitions: Record<ProductionBusinessStatus, ProductionBusinessStatus[]> = {
  NOT_ORDERED: ['NOT_ORDERED', 'ORDERED', 'VOID'],
  ORDERED: ['ORDERED', 'MASS_PRODUCED', 'VOID'],
  MASS_PRODUCED: ['MASS_PRODUCED', 'VOID'],
  VOID: ['VOID', 'NOT_ORDERED'],
};

export function createEmptyBusinessSnapshot(): ProductionBusinessSnapshot {
  return {
    status: 'NOT_ORDERED',
    taskRows: [],
    materialRows: [],
    inspectionRows: [],
    outputRows: [],
    formula: { expression: '', ratio: 0, tolerance: 0.01 },
    signatures: { operator: '', quality: '', reviewer: '', signedAt: '' },
    attachments: [],
    links: {
      rawMaterialIssued: false,
      finishedGoodsStored: false,
      differencePosted: false,
      recipeReferenced: false,
    },
    audit: [],
  };
}

function asString(value: unknown) {
  return typeof value === 'string' ? value : '';
}

function asNumber(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

export function normalizeBusinessSnapshot(value: unknown): ProductionBusinessSnapshot {
  const empty = createEmptyBusinessSnapshot();
  if (!value || typeof value !== 'object') return empty;
  const source = value as Partial<ProductionBusinessSnapshot>;
  return {
    ...empty,
    ...source,
    taskRows: Array.isArray(source.taskRows) ? source.taskRows.map((row) => ({
      id: asString(row.id) || crypto.randomUUID(),
      process: asString(row.process), owner: asString(row.owner),
      status: row.status || 'PENDING', plannedDate: asString(row.plannedDate),
    })) : [],
    materialRows: Array.isArray(source.materialRows) ? source.materialRows.map((row) => ({
      id: asString(row.id) || crypto.randomUUID(), material: asString(row.material),
      batch: asString(row.batch), theoretical: asNumber(row.theoretical), actual: asNumber(row.actual),
    })) : [],
    inspectionRows: Array.isArray(source.inspectionRows) ? source.inspectionRows.map((row) => ({
      id: asString(row.id) || crypto.randomUUID(), date: asString(row.date),
      inspector: asString(row.inspector), result: row.result || 'PENDING', note: asString(row.note),
    })) : [],
    outputRows: Array.isArray(source.outputRows) ? source.outputRows.map((row) => ({
      id: asString(row.id) || crypto.randomUUID(), batch: asString(row.batch),
      quantity: asNumber(row.quantity), packageSpec: asString(row.packageSpec), packageDate: asString(row.packageDate),
    })) : [],
    formula: { ...empty.formula, ...(source.formula || {}), expression: asString(source.formula?.expression), ratio: asNumber(source.formula?.ratio), tolerance: asNumber(source.formula?.tolerance) },
    signatures: { ...empty.signatures, ...(source.signatures || {}) },
    attachments: Array.isArray(source.attachments) ? source.attachments.map((item) => ({ id: asString(item.id) || crypto.randomUUID(), name: asString(item.name), kind: item.kind || 'PROCESS_ATTACHMENT' })) : [],
    links: { ...empty.links, ...(source.links || {}) },
    audit: Array.isArray(source.audit) ? source.audit : [],
  };
}

export function validateBusinessSnapshot(value: ProductionBusinessSnapshot): string | undefined {
  const task = value.taskRows.find((row) => !row.process.trim() || !row.owner.trim());
  if (task) return '生产任务的工序名称和负责人不能为空';
  const material = value.materialRows.find((row) => !row.material.trim() || !row.batch.trim());
  if (material) return '投料记录的原料名称和批次不能为空';
  if (value.materialRows.some((row) => row.theoretical < 0 || row.actual < 0)) {
    return '投料用量不能小于 0';
  }
  const output = value.outputRows.find((row) => !row.batch.trim() || row.quantity < 0);
  if (output) return '实际产量需要填写成品批次，且不能小于 0';
  const inspection = value.inspectionRows.find((row) => !row.date || !row.inspector.trim());
  if (inspection) return '巡检记录的日期和巡检人不能为空';
  if (value.formula.ratio < 0 || value.formula.ratio > 100 || value.formula.tolerance < 0 || value.formula.tolerance > 100) {
    return '配方比例和容差必须在 0 到 100 之间';
  }
  if ((value.signatures.operator && !value.signatures.quality) || (!value.signatures.operator && value.signatures.quality)) {
    return '签名确认需要同时填写生产操作人和品管签名';
  }
  return undefined;
}

interface ProductionBusinessPanelProps {
  value: ProductionBusinessSnapshot;
  editable: boolean;
  onChange: (value: ProductionBusinessSnapshot) => void;
}

export function ProductionBusinessPanel({ value, editable, onChange }: ProductionBusinessPanelProps) {
  const attachmentInput = useRef<HTMLInputElement>(null);
  const operator = useAuthStore((state) => state.user?.displayName || state.user?.username || '当前登录用户');
  const commit = (next: ProductionBusinessSnapshot, action: string, detail: string) => {
    onChange({
      ...next,
      audit: [...next.audit, {
        id: crypto.randomUUID(), action, detail,
        createdAt: new Date().toISOString(), operator,
      }],
    });
  };
  const update = <K extends keyof ProductionBusinessSnapshot>(key: K, next: ProductionBusinessSnapshot[K]) => {
    onChange({ ...value, [key]: next });
  };
  const addTask = () => update('taskRows', [...value.taskRows, { id: crypto.randomUUID(), process: '', owner: '', status: 'PENDING', plannedDate: '' }]);
  const addMaterial = () => update('materialRows', [...value.materialRows, { id: crypto.randomUUID(), material: '', batch: '', theoretical: 0, actual: 0 }]);
  const addInspection = () => update('inspectionRows', [...value.inspectionRows, { id: crypto.randomUUID(), date: '', inspector: '', result: 'PENDING', note: '' }]);
  const addOutput = () => update('outputRows', [...value.outputRows, { id: crypto.randomUUID(), batch: '', quantity: 0, packageSpec: '', packageDate: '' }]);

  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Card size="small" title="业务状态与流程" extra={<Tag color={value.status === 'VOID' ? 'error' : value.status === 'MASS_PRODUCED' ? 'success' : 'processing'}>{statusLabels[value.status]}</Tag>}>
      <Space wrap>
        <Select disabled={!editable} value={value.status} style={{ width: 140 }} options={allowedStatusTransitions[value.status].map((key) => ({ value: key, label: statusLabels[key] }))} onChange={(status: ProductionBusinessStatus) => commit({ ...value, status }, '业务状态变更', `状态变更为${statusLabels[status]}`)} />
        <Button disabled={!editable || value.status !== 'NOT_ORDERED'} onClick={() => commit({ ...value, status: 'ORDERED' }, '提交下单', '生产单已进入已下单状态')}>提交下单</Button>
        <Button disabled={!editable || value.status !== 'ORDERED'} onClick={() => commit({ ...value, status: 'MASS_PRODUCED' }, '标记量产', '生产单已进入已量产状态')}>标记量产</Button>
        <Button danger disabled={!editable || value.status === 'VOID'} onClick={() => commit({ ...value, status: 'VOID' }, '生产单作废', '作废前保留业务快照和操作留痕')}>作废</Button>
        <Button icon={<RollbackOutlined />} disabled={!editable || value.status !== 'VOID'} onClick={() => commit({ ...value, status: 'NOT_ORDERED' }, '撤销作废', '生产单恢复为未下单')}>撤销作废</Button>
      </Space>
      <Alert style={{ marginTop: 12 }} type="info" showIcon message="所有状态变更都会写入操作留痕；已量产和已作废记录仍可查看历史版本。" />
    </Card>

    <Card size="small" title="生产任务单 / 工序" extra={<Space size={6}><Button size="small" icon={<PlusOutlined />} disabled={!editable} onClick={addTask}>新增任务</Button><Button size="small" disabled={!editable || !value.taskRows.length} onClick={() => commit({ ...value, taskRows: value.taskRows.map((row) => ({ ...row, status: 'COMPLETED' })) }, '批量完成工序', `已批量完成${value.taskRows.length}条工序`)}>批量完成工序</Button></Space>}>
      {value.taskRows.length ? value.taskRows.map((row, index) => <Row gutter={8} key={row.id} style={{ marginBottom: 8 }}>
        <Col flex="1 1 180px"><Input disabled={!editable} aria-label={`工序-${index + 1}`} placeholder="工序名称" value={row.process} onChange={(e) => update('taskRows', value.taskRows.map((item) => item.id === row.id ? { ...item, process: e.target.value } : item))} /></Col>
        <Col flex="1 1 140px"><Input disabled={!editable} aria-label={`负责人-${index + 1}`} placeholder="负责人" value={row.owner} onChange={(e) => update('taskRows', value.taskRows.map((item) => item.id === row.id ? { ...item, owner: e.target.value } : item))} /></Col>
        <Col flex="0 1 130px"><Select disabled={!editable} aria-label={`任务状态-${index + 1}`} value={row.status} style={{ width: '100%' }} options={Object.entries(taskStatusLabels).map(([value, label]) => ({ value, label }))} onChange={(status) => update('taskRows', value.taskRows.map((item) => item.id === row.id ? { ...item, status } : item))} /></Col>
        <Col flex="0 1 145px"><Input disabled={!editable} type="date" aria-label={`计划日期-${index + 1}`} value={row.plannedDate} onChange={(e) => update('taskRows', value.taskRows.map((item) => item.id === row.id ? { ...item, plannedDate: e.target.value } : item))} /></Col>
        <Col flex="0 0 36px"><Button danger type="text" aria-label={`删除任务-${index + 1}`} icon={<DeleteOutlined />} disabled={!editable} onClick={() => update('taskRows', value.taskRows.filter((item) => item.id !== row.id))} /></Col>
      </Row>) : <Typography.Text type="secondary">暂无生产任务，点击“新增任务”建立工序和负责人。</Typography.Text>}
    </Card>

    <Card size="small" title="投料记录 / 原料批次与实际投料" extra={<Space size={6}><Button size="small" icon={<PlusOutlined />} disabled={!editable} onClick={addMaterial}>新增投料</Button><Button size="small" disabled={!editable || !value.materialRows.length} onClick={() => commit(value, '批量确认投料', `已批量确认${value.materialRows.length}条投料记录`)}>批量确认投料</Button></Space>}>
      {value.materialRows.length ? value.materialRows.map((row, index) => <Row gutter={8} key={row.id} style={{ marginBottom: 8 }}>
        <Col flex="1 1 170px"><Input disabled={!editable} aria-label={`原料-${index + 1}`} placeholder="原料名称" value={row.material} onChange={(e) => update('materialRows', value.materialRows.map((item) => item.id === row.id ? { ...item, material: e.target.value } : item))} /></Col>
        <Col flex="1 1 150px"><Input disabled={!editable} aria-label={`批次-${index + 1}`} placeholder="原料批次" value={row.batch} onChange={(e) => update('materialRows', value.materialRows.map((item) => item.id === row.id ? { ...item, batch: e.target.value } : item))} /></Col>
        <Col flex="0 1 120px"><InputNumber disabled={!editable} aria-label={`理论用量-${index + 1}`} min={0} style={{ width: '100%' }} placeholder="理论用量" value={row.theoretical} onChange={(theoretical) => update('materialRows', value.materialRows.map((item) => item.id === row.id ? { ...item, theoretical: Number(theoretical || 0) } : item))} /></Col>
        <Col flex="0 1 120px"><InputNumber disabled={!editable} aria-label={`实际用量-${index + 1}`} min={0} style={{ width: '100%' }} placeholder="实际用量" value={row.actual} onChange={(actual) => update('materialRows', value.materialRows.map((item) => item.id === row.id ? { ...item, actual: Number(actual || 0) } : item))} /></Col>
        <Col flex="0 0 36px"><Button danger type="text" aria-label={`删除投料-${index + 1}`} icon={<DeleteOutlined />} disabled={!editable} onClick={() => update('materialRows', value.materialRows.filter((item) => item.id !== row.id))} /></Col>
      </Row>) : <Typography.Text type="secondary">暂无投料记录。</Typography.Text>}
    </Card>

    <Card size="small" title="M687 配方比例与公式 / 实际产量与包装">
      <Row gutter={12}>
        <Col xs={24} md={12}><Typography.Text type="secondary">计算公式</Typography.Text><Input disabled={!editable} placeholder="例如 A / B * 100%" value={value.formula.expression} onChange={(e) => update('formula', { ...value.formula, expression: e.target.value })} /></Col>
        <Col xs={12} md={6}><Typography.Text type="secondary">配方比例（%）</Typography.Text><InputNumber disabled={!editable} min={0} max={100} style={{ width: '100%' }} value={value.formula.ratio} onChange={(ratio) => update('formula', { ...value.formula, ratio: Number(ratio || 0) })} /></Col>
        <Col xs={12} md={6}><Typography.Text type="secondary">容差（%）</Typography.Text><InputNumber disabled={!editable} min={0} max={100} style={{ width: '100%' }} value={value.formula.tolerance} onChange={(tolerance) => update('formula', { ...value.formula, tolerance: Number(tolerance || 0) })} /></Col>
      </Row>
      <Space style={{ marginTop: 14, marginBottom: 8 }}><Typography.Text strong>包装产量</Typography.Text><Button size="small" icon={<PlusOutlined />} disabled={!editable} onClick={addOutput}>新增产量</Button></Space>
      {value.outputRows.map((row, index) => <Row gutter={8} key={row.id} style={{ marginBottom: 8 }}>
        <Col flex="1 1 150px"><Input disabled={!editable} aria-label={`成品批次-${index + 1}`} placeholder="成品批次" value={row.batch} onChange={(e) => update('outputRows', value.outputRows.map((item) => item.id === row.id ? { ...item, batch: e.target.value } : item))} /></Col>
        <Col flex="0 1 120px"><InputNumber disabled={!editable} aria-label={`实际产量-${index + 1}`} min={0} style={{ width: '100%' }} placeholder="实际产量" value={row.quantity} onChange={(quantity) => update('outputRows', value.outputRows.map((item) => item.id === row.id ? { ...item, quantity: Number(quantity || 0) } : item))} /></Col>
        <Col flex="1 1 150px"><Input disabled={!editable} aria-label={`包装规格-${index + 1}`} placeholder="包装规格" value={row.packageSpec} onChange={(e) => update('outputRows', value.outputRows.map((item) => item.id === row.id ? { ...item, packageSpec: e.target.value } : item))} /></Col>
        <Col flex="0 1 145px"><Input disabled={!editable} type="date" aria-label={`包装日期-${index + 1}`} value={row.packageDate} onChange={(e) => update('outputRows', value.outputRows.map((item) => item.id === row.id ? { ...item, packageDate: e.target.value } : item))} /></Col>
        <Col flex="0 0 36px"><Button danger type="text" aria-label={`删除产量-${index + 1}`} icon={<DeleteOutlined />} disabled={!editable} onClick={() => update('outputRows', value.outputRows.filter((item) => item.id !== row.id))} /></Col>
      </Row>)}
    </Card>

    <Card size="small" title="巡检记录" extra={<Space size={6}><Button size="small" icon={<PlusOutlined />} disabled={!editable} onClick={addInspection}>新增巡检</Button><Button size="small" disabled={!editable || !value.inspectionRows.length} onClick={() => commit(value, '批量确认巡检', `已批量确认${value.inspectionRows.length}条巡检记录`)}>批量确认巡检</Button></Space>}>
      {value.inspectionRows.length ? value.inspectionRows.map((row, index) => <Row gutter={8} key={row.id} style={{ marginBottom: 8 }}>
        <Col flex="0 1 145px"><Input disabled={!editable} type="date" aria-label={`巡检日期-${index + 1}`} value={row.date} onChange={(e) => update('inspectionRows', value.inspectionRows.map((item) => item.id === row.id ? { ...item, date: e.target.value } : item))} /></Col>
        <Col flex="1 1 140px"><Input disabled={!editable} aria-label={`巡检人-${index + 1}`} placeholder="巡检人" value={row.inspector} onChange={(e) => update('inspectionRows', value.inspectionRows.map((item) => item.id === row.id ? { ...item, inspector: e.target.value } : item))} /></Col>
        <Col flex="0 1 120px"><Select disabled={!editable} value={row.result} style={{ width: '100%' }} options={[{ value: 'PENDING', label: '待确认' }, { value: 'PASS', label: '合格' }, { value: 'FAIL', label: '不合格' }]} onChange={(result) => update('inspectionRows', value.inspectionRows.map((item) => item.id === row.id ? { ...item, result } : item))} /></Col>
        <Col flex="1 1 220px"><Input disabled={!editable} placeholder="巡检备注" value={row.note} onChange={(e) => update('inspectionRows', value.inspectionRows.map((item) => item.id === row.id ? { ...item, note: e.target.value } : item))} /></Col>
        <Col flex="0 0 36px"><Button danger type="text" aria-label={`删除巡检-${index + 1}`} icon={<DeleteOutlined />} disabled={!editable} onClick={() => update('inspectionRows', value.inspectionRows.filter((item) => item.id !== row.id))} /></Col>
      </Row>) : <Typography.Text type="secondary">暂无巡检记录。</Typography.Text>}
    </Card>

    <Card size="small" title="附件与签名审计">
      <Space wrap>
        <Button icon={<UploadOutlined />} disabled={!editable} onClick={() => attachmentInput.current?.click()}>上传实际投料单照片/工艺附件</Button>
        <input ref={attachmentInput} hidden type="file" accept="image/*,.pdf,.doc,.docx,.xlsx" onChange={(event) => {
          const file = event.target.files?.[0];
          if (!file) return;
          if (value.attachments.some((item) => item.name === file.name)) {
            event.target.value = '';
            return;
          }
          update('attachments', [...value.attachments, { id: crypto.randomUUID(), name: file.name, kind: file.type.startsWith('image/') ? 'MATERIAL_PHOTO' : 'PROCESS_ATTACHMENT' }]);
          event.target.value = '';
        }} />
        {value.attachments.map((item) => <Tag key={item.id} closable={editable} onClose={() => update('attachments', value.attachments.filter((entry) => entry.id !== item.id))}>{item.kind === 'MATERIAL_PHOTO' ? '投料照片' : '工艺附件'} · {item.name}</Tag>)}
      </Space>
      <Row gutter={8} style={{ marginTop: 12 }}>
        <Col xs={24} md={6}><Input disabled={!editable} placeholder="生产操作人签名" value={value.signatures.operator} onChange={(e) => update('signatures', { ...value.signatures, operator: e.target.value })} /></Col>
        <Col xs={24} md={6}><Input disabled={!editable} placeholder="品管签名" value={value.signatures.quality} onChange={(e) => update('signatures', { ...value.signatures, quality: e.target.value })} /></Col>
        <Col xs={24} md={6}><Input disabled={!editable} placeholder="审核人签名" value={value.signatures.reviewer} onChange={(e) => update('signatures', { ...value.signatures, reviewer: e.target.value })} /></Col>
        <Col xs={24} md={6}><Input disabled={!editable} type="datetime-local" value={value.signatures.signedAt} onChange={(e) => update('signatures', { ...value.signatures, signedAt: e.target.value })} /></Col>
      </Row>
      <Button style={{ marginTop: 12 }} icon={<CheckCircleOutlined />} disabled={!editable || !value.signatures.operator || !value.signatures.quality} onClick={() => commit(value, '签名确认', '生产操作人与品管签名已确认')}>确认签名并写入审计</Button>
    </Card>

    <Card size="small" title="库存与研发联动">
      <Space wrap>
        {([
          ['rawMaterialIssued', '原料扣减', '原料库存已扣减'],
          ['finishedGoodsStored', '成品入库', '成品库存已入库'],
          ['differencePosted', '差额过账', '差额已生成过账流水'],
          ['recipeReferenced', '引用正式配方', '已锁定研发正式配方引用'],
        ] as const).map(([key, label, detail]) => <Button key={key} icon={<LinkOutlined />} disabled={!editable || value.links[key]} onClick={() => commit({ ...value, links: { ...value.links, [key]: true } }, label, detail)}>{value.links[key] ? `已${label}` : label}</Button>)}
      </Space>
      <Typography.Paragraph type="secondary" style={{ margin: '12px 0 0' }}>联动动作按生产单快照幂等：同一动作再次点击不会重复生成扣减、入库或过账记录。</Typography.Paragraph>
    </Card>

    <Card size="small" title="操作留痕">
      {value.audit.length ? value.audit.slice().reverse().map((item) => <div key={item.id} style={{ padding: '8px 0', borderBottom: '1px solid #f0f0f0' }}><Typography.Text strong>{item.action}</Typography.Text><Typography.Text type="secondary"> · {item.operator} · {new Date(item.createdAt).toLocaleString('zh-CN')}</Typography.Text><div><Typography.Text type="secondary">{item.detail}</Typography.Text></div></div>) : <Typography.Text type="secondary">暂无业务操作留痕。</Typography.Text>}
    </Card>
  </Space>;
}
