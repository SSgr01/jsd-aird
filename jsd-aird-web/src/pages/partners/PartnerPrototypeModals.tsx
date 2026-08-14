import { DeleteOutlined, PlusOutlined, SaveOutlined, UpOutlined } from '@ant-design/icons';
import { Button, Col, DatePicker, Form, Input, Modal, Row, Select, message } from 'antd';
import dayjs from '@/utils/dayjs';
import type { Dayjs } from 'dayjs';
import { useEffect, useState } from 'react';
import { createCommunication, createRequirement, updateCommunication, updateRequirement } from '@/services/partners/crm-api';
import type { Communication, CommunicationInput, Requirement, RequirementInput } from '@/services/partners/crm-api';
import { getProjects } from '@/services/project/project-api';
import type { Project } from '@/services/project/project-api';
import {
  changeContactStatus,
  createContact,
  createPartner,
  getPartnerContacts,
  updateContact,
  updatePartner,
} from '@/services/partners/partner-api';
import type {
  BusinessPartner,
  PartnerContact,
} from '@/services/partners/partner-api';
import './partner-modals.css';

type Props = {
  mode?: 'customer' | 'requirement' | 'followup';
  partner?: BusinessPartner;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
  requirement?: Requirement;
  communication?: Communication;
};
type CustomField = { key: string; value: string };
type CustomFieldEditorProps = {
  fields: CustomField[];
  onChange: (fields: CustomField[]) => void;
};
type Person = {
  id?: string;
  version?: number;
  name?: string;
  title?: string;
  department?: string;
  wechat?: string;
  phone?: string;
  email?: string;
  teamMembers?: string[];
  manualTeamMembers?: string[];
  projectIds?: string[];
  expanded?: boolean;
  customFields?: CustomField[];
};
const customerLevels = ['重点客户', '普通客户', '潜在客户'];
const cooperationStatuses = ['潜在客户', '需求沟通', '合作中', '暂停', '已结束'];
const emptyPerson = (): Person => ({
  expanded: false,
  teamMembers: [],
  manualTeamMembers: [],
  projectIds: [],
});

// 合并所选项目的团队成员：保留手动添加的成员，项目成员追加到末尾并去重
function mergeProjectTeamMembers(current: string[], projectIds: string[], projects: Project[]): string[] {
  const selected = projects.filter((p) => projectIds.includes(String(p.id)));
  const fromProjects = [...new Set(selected.flatMap((p) => p.teamMembers ?? []))];
  const manual = current.filter((m) => !fromProjects.includes(m));
  return [...new Set([...manual, ...fromProjects])];
}

function toCustomFieldList(source?: Record<string, unknown>): CustomField[] {
  return Object.entries(source ?? {}).map(([key, value]) => ({
    key,
    value: value == null ? '' : String(value),
  }));
}

function toCustomFieldRecord(fields?: CustomField[]): Record<string, unknown> {
  return Object.fromEntries(
    (fields ?? [])
      .map((field) => [field.key.trim(), field.value] as const)
      .filter(([key]) => key !== ''),
  );
}
type CustomerValues = {
  name: string;
  industry?: string;
  level?: string;
  cooperation?: string;
  region?: string;
  business?: string;
};
type RequirementValues = {
  title: string;
  raisedAt?: Dayjs;
  deliveryDate?: Dayjs;
  urgency?: string;
  status?: string;
  customStatusName?: string;
  projectId?: string;
  rawRequirement?: string;
};
type FollowupValues = {
  name: string;
  time: Dayjs;
  method: string;
  owner?: string;
  content: string;
};

export function PartnerPrototypeModal({
  mode = 'customer',
  partner,
  open,
  onClose,
  onSaved,
  requirement,
  communication,
}: Props) {
  if (mode === 'requirement')
    return <RequirementModal partner={partner} requirement={requirement} open={open} onClose={onClose} onSaved={onSaved} />;
  if (mode === 'followup')
    return <FollowupModal partner={partner} communication={communication} open={open} onClose={onClose} onSaved={onSaved} />;
  return <CustomerModal partner={partner} open={open} onClose={onClose} onSaved={onSaved} />;
}

function CustomerModal({ partner, open, onClose, onSaved }: Omit<Props, 'mode'>) {
  const [form] = Form.useForm<CustomerValues>();
  const [people, setPeople] = useState<Person[]>([]);
  const [partnerFields, setPartnerFields] = useState<CustomField[]>([]);
  const [loadedContacts, setLoadedContacts] = useState<PartnerContact[]>([]);
  const [projectOptions, setProjectOptions] = useState<{ value: string; label: string }[]>([]);
  const [allProjects, setAllProjects] = useState<Project[]>([]);

  useEffect(() => {
    if (!open) return;
    form.setFieldsValue(
      partner
        ? {
            name: partner.name,
            industry: partner.industry,
            region: partner.address,
            business: partner.mainBusiness,
            level: partner.customerLevel,
            cooperation: partner.cooperationStatus,
          }
        : { name: undefined, industry: undefined, region: undefined, business: undefined },
    );
    setPeople([]);
    setLoadedContacts([]);
    setProjectOptions([]);
    setAllProjects([]);
    setPartnerFields(toCustomFieldList(partner?.customFields));
    if (!partner) return;
    let cancelled = false;
    void getPartnerContacts(partner.id)
      .then((contactList) => {
        if (cancelled) return;
        setLoadedContacts(contactList);
        setPeople(
          contactList
            .filter((contact) => contact.status === 'ACTIVE')
            .map((contact) => ({
              id: contact.id,
              version: contact.version,
              name: contact.name,
              title: contact.title,
              department: contact.department,
              phone: contact.phone,
              email: contact.email,
              wechat: contact.wechat,
              teamMembers: contact.members ? contact.members.split(/[,，]/).map((s) => s.trim()).filter(Boolean) : [],
              manualTeamMembers: contact.members ? contact.members.split(/[,，]/).map((s) => s.trim()).filter(Boolean) : [],
              projectIds: contact.assignedProjectIds ?? [],
              expanded: false,
              customFields: toCustomFieldList(contact.customFields),
            })),
        );
      })
      .catch(() => {
        if (!cancelled) message.error('负责人加载失败');
      });
    return () => {
      cancelled = true;
    };
  }, [form, open, partner]);

  useEffect(() => {
    if (!open) return;
    void getProjects({ page: 1, size: 1000 })
      .then((res) => {
        setAllProjects(res.items);
        setProjectOptions(res.items.map((p) => ({ value: p.id, label: `${p.projectCode} ${p.name}` })));
      })
      .catch(() => message.error('项目列表加载失败'));
  }, [open]);

  // 项目列表加载完成后，按当前手动成员 + 已选项目重新计算团队成员
  useEffect(() => {
    if (allProjects.length === 0) return;
    setPeople((prev) =>
      prev.map((p) => ({
        ...p,
        teamMembers: mergeProjectTeamMembers(p.manualTeamMembers ?? [], p.projectIds ?? [], allProjects),
      })),
    );
  }, [allProjects]);

  const updatePerson = (index: number, patch: Partial<Person>) =>
    setPeople((prev) => prev.map((p, i) => (i === index ? { ...p, ...patch } : p)));

  const save = async () => {
    const v = await form.validateFields();
    const input = {
      partnerCode: partner?.partnerCode ?? `CUS-${Date.now().toString().slice(-10)}`,
      name: v.name,
      industry: v.industry,
      address: v.region,
      remark: partner?.remark,
      customerLevel: v.level,
      cooperationStatus: v.cooperation,
      mainBusiness: v.business,
      customFields: toCustomFieldRecord(partnerFields),
      version: partner?.version,
    };
    const partnerId = partner?.id ?? (await createPartner(input)).id;
    if (partner) await updatePartner(partner.id, input);
    for (const [index, person] of people.entries())
      if (person.name) {
        const contactInput = {
          name: person.name,
          department: person.department,
          title: person.title,
          phone: person.phone,
          email: person.email,
          wechat: person.wechat,
          responsibility: undefined,
          members: (person.teamMembers ?? []).join(','),
          assignedProjectIds: person.projectIds ?? [],
          customFields: toCustomFieldRecord(person.customFields),
          primaryContact: index === 0,
          version: person.version,
        };
        if (person.id) await updateContact(partnerId, person.id, contactInput);
        else await createContact(partnerId, contactInput);
      }
    if (partner)
      for (const contact of loadedContacts.filter(
        (item) => item.status === 'ACTIVE' && !people.some((person) => person.id === item.id),
      ))
        await changeContactStatus(partner.id, contact);
    form.resetFields();
    setPeople([]);
    setLoadedContacts([]);
    setProjectOptions([]);
    setAllProjects([]);
    setPartnerFields([]);
    onSaved();
    onClose();
  };

  return (
    <Modal
      className="cm-prototype-modal cm-customer-modal"
      width={960}
      open={open}
      onCancel={onClose}
      title={partner ? '编辑客户' : '新建客户'}
      footer={
        <Button type="primary" icon={<SaveOutlined />} onClick={() => void save()}>
          保存
        </Button>
      }
    >
      <div className="cm-modal-columns cm-customer-columns">
        <section className="cm-modal-panel">
          <h3>公司资料</h3>
          <p>填写客户公司的基础资料。</p>
          <Form form={form} layout="vertical">
            <Form.Item name="name" label="公司名称" rules={[{ required: true }]}>
              <Input placeholder="请输入公司名称" />
            </Form.Item>
            <div className="cm-form-grid">
              <Form.Item name="industry" label="所属行业">
                <Input placeholder="请输入所属行业" />
              </Form.Item>
              <Form.Item name="level" label="客户等级" initialValue="未分类">
                <Select options={['未分类', ...customerLevels].map((value) => ({ value }))} />
              </Form.Item>
              <Form.Item name="cooperation" label="合作状态" initialValue="潜在客户">
                <Select options={cooperationStatuses.map((value) => ({ value }))} />
              </Form.Item>
              <Form.Item name="region" label="所在地区">
                <Input placeholder="请输入所在地区" />
              </Form.Item>
            </div>
            <Form.Item name="business" label="主营业务">
              <Input.TextArea rows={3} placeholder="请输入主营业务" />
            </Form.Item>
          </Form>
          <CustomFieldEditor fields={partnerFields} onChange={setPartnerFields} />
        </section>

        <section className="cm-modal-panel">
          <div className="cm-panel-head">
            <div>
              <h3>负责人</h3>
              <p>维护负责人资料，并从项目列表选择负责项目。</p>
            </div>
            <Button icon={<PlusOutlined />} onClick={() => setPeople([...people, emptyPerson()])}>
              新增负责人
            </Button>
          </div>
          {people.length === 0 && <div className="cm-related-empty">暂无负责人，可按需新增</div>}
          {people.map((person, index) => (
            <div className="cm-repeat-card" key={index}>
              <div className="cm-repeat-title">
                <div
                  className="cm-repeat-title-text"
                  onClick={() => updatePerson(index, { expanded: !person.expanded })}
                >
                  <strong>负责人 {index + 1}</strong>
                  <span className="cm-repeat-subtitle">
                    已选 {(person.projectIds ?? []).length} 个项目
                  </span>
                  <UpOutlined
                    className={`cm-repeat-chevron ${person.expanded === false ? 'collapsed' : ''}`}
                  />
                </div>
                <Button
                  danger
                  type="primary"
                  icon={<DeleteOutlined />}
                  onClick={() => setPeople(people.filter((_, i) => i !== index))}
                >
                  删除
                </Button>
              </div>
              {person.expanded !== false && (
                <>
                  <div className="cm-form-grid cm-form-grid-3">
                    <label>
                      姓名
                      <Input
                        value={person.name}
                        onChange={(e) => updatePerson(index, { name: e.target.value })}
                      />
                    </label>
                    <label>
                      职位
                      <Input
                        value={person.title}
                        onChange={(e) => updatePerson(index, { title: e.target.value })}
                      />
                    </label>
                    <label>
                      部门
                      <Input
                        value={person.department}
                        onChange={(e) => updatePerson(index, { department: e.target.value })}
                      />
                    </label>
                  </div>
                  <div className="cm-form-grid cm-form-grid-3">
                    <label>
                      手机
                      <Input
                        value={person.phone}
                        onChange={(e) => updatePerson(index, { phone: e.target.value })}
                      />
                    </label>
                    <label>
                      微信
                      <Input
                        value={person.wechat}
                        onChange={(e) => updatePerson(index, { wechat: e.target.value })}
                      />
                    </label>
                    <label>
                      邮箱
                      <Input
                        value={person.email}
                        onChange={(e) => updatePerson(index, { email: e.target.value })}
                      />
                    </label>
                  </div>
                  <label>
                    团队成员
                    <Input
                      placeholder="多个成员用逗号或顿号分隔"
                      value={(person.teamMembers ?? []).join(',')}
                      onChange={(e) => {
                        const manual = e.target.value.split(/[,，]/).map((s) => s.trim()).filter(Boolean);
                        updatePerson(index, {
                          manualTeamMembers: manual,
                          teamMembers: mergeProjectTeamMembers(manual, person.projectIds ?? [], allProjects),
                        });
                      }}
                    />
                  </label>
                  <label>
                    负责项目（可多选）
                    <Select
                      mode="multiple"
                      allowClear
                      showSearch
                      placeholder="搜索并选择项目"
                      value={person.projectIds}
                      onChange={(value) => {
                        const ids = (value as string[]) ?? [];
                        updatePerson(index, {
                          projectIds: ids,
                          teamMembers: mergeProjectTeamMembers(person.manualTeamMembers ?? [], ids, allProjects),
                        });
                      }}
                      options={projectOptions}
                      optionFilterProp="label"
                    />
                  </label>
                  <CustomFieldEditor
                    fields={person.customFields ?? []}
                    onChange={(fields) => updatePerson(index, { customFields: fields })}
                  />
                </>
              )}
            </div>
          ))}
        </section>
      </div>
    </Modal>
  );
}

function RequirementModal({ partner, requirement, open, onClose, onSaved }: Omit<Props, 'mode'>) {
  const [form] = Form.useForm<RequirementValues>();
  const [requirementCustomFields, setRequirementCustomFields] = useState<CustomField[]>([]);
  const [allProjects, setAllProjects] = useState<Project[]>([]);
  useEffect(() => {
    if (!open) return;
    void getProjects({ page: 1, size: 1000 }).then((res) => setAllProjects(res.items));
    if (requirement) {
      const statusName = requirement.customStatusName
        ? '自定义状态'
        : (requirementStateMap[requirement.status] ?? '自定义状态');
      form.setFieldsValue({
        title: requirement.title,
        rawRequirement: requirement.rawRequirement,
        urgency: requirement.urgency,
        raisedAt: requirement.raisedAt ? dayjs(requirement.raisedAt) : undefined,
        deliveryDate: requirement.deliveryDate ? dayjs(requirement.deliveryDate) : undefined,
        status: statusName,
        customStatusName: requirement.customStatusName,
        projectId: requirement.projectId,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ status: '自定义状态', customStatusName: '草稿', urgency: '中', raisedAt: dayjs() });
    }
    setRequirementCustomFields(toCustomFieldList(requirement?.customFields));
  }, [form, open, requirement]);
  const save = async () => {
    const v = await form.validateFields();
    if (!partner) return;
    const isCustomStatus = v.status === '自定义状态';
    const payload: RequirementInput = {
      partnerId: partner.id,
      title: v.title,
      rawRequirement: v.rawRequirement,
      urgency: v.urgency,
      raisedAt: v.raisedAt?.format('YYYY-MM-DD'),
      deliveryDate: v.deliveryDate?.format('YYYY-MM-DD'),
      status: (isCustomStatus ? 'DRAFT' : (requirementStateReverse[v.status ?? '草稿'] ?? 'DRAFT')) as Requirement['status'],
      customStatusName: isCustomStatus ? v.customStatusName : undefined,
      projectId: v.projectId,
      metrics: [],
      customFields: toCustomFieldRecord(requirementCustomFields),
      version: requirement?.version ?? 0,
    };
    if (requirement) await updateRequirement(requirement.id, payload);
    else await createRequirement(payload);
    form.resetFields();
    onSaved();
    onClose();
  };
  return (
    <Modal
      className="cm-prototype-modal cm-form-modal"
      width={960}
      open={open}
      onCancel={onClose}
      title={requirement ? '编辑客户需求' : '新建客户需求'}
      footer={
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" onClick={() => void save()}>
            保存
          </Button>
        </div>
      }
    >
      <Form form={form} layout="vertical">
        <Row gutter={24}>
          <Col span={12}>
            <Form.Item name="title" label="需求名称" rules={[{ required: true }]}>
              <Input placeholder="未命名记录" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="projectId" label="关联项目">
              <Select
                showSearch
                placeholder="搜索并选择项目"
                optionFilterProp="label"
                options={allProjects.map((p) => ({ label: `${p.projectCode} ${p.name}`, value: p.id }))}
              />
            </Form.Item>
          </Col>
        </Row>
        <Row gutter={24}>
          <Col span={6}>
            <Form.Item name="raisedAt" label="提出日期">
              <DatePicker format="YYYY/MM/DD" style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="deliveryDate" label="预计完成日期">
              <DatePicker format="YYYY/MM/DD" style={{ width: '100%' }} placeholder="年/月/日" />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="urgency" label="紧急程度">
              <Select options={['高', '中', '低'].map((value) => ({ value }))} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="status" label="状态">
              <Select options={['草稿', '已确认', '已立项', '已完成', '已取消', '自定义状态'].map((value) => ({ value }))} />
            </Form.Item>
          </Col>
        </Row>
        <Form.Item shouldUpdate={(prev, curr) => prev.status !== curr.status} noStyle>
          {() =>
            form.getFieldValue('status') === '自定义状态' ? (
              <Form.Item
                name="customStatusName"
                label="自定义状态名称"
                rules={[{ required: true, message: '请输入自定义状态名称' }]}
              >
                <Input placeholder="请输入自定义状态名称" />
              </Form.Item>
            ) : null
          }
        </Form.Item>
        <Form.Item name="rawRequirement" label="需求总结">
          <Input.TextArea rows={4} placeholder="请输入需求总结" />
        </Form.Item>
        <div className="cm-edit-divider"><span>自定义字段</span></div>
        <div className="cm-custom-fields">
          {requirementCustomFields.map((field, index) => (
            <div className="cm-custom-field-row" key={index}>
              <Input
                value={field.key}
                placeholder="字段名"
                onChange={(e) => {
                  const next = [...requirementCustomFields];
                  next[index] = { ...next[index]!, key: e.target.value };
                  setRequirementCustomFields(next);
                }}
              />
              <Input
                value={field.value ?? ''}
                placeholder="字段值"
                onChange={(e) => {
                  const next = [...requirementCustomFields];
                  next[index] = { ...next[index]!, value: e.target.value };
                  setRequirementCustomFields(next);
                }}
              />
              <Button danger icon={<DeleteOutlined />} onClick={() => setRequirementCustomFields(requirementCustomFields.filter((_, i) => i !== index))} />
            </div>
          ))}
          <Button className="cm-custom-field" icon={<PlusOutlined />} onClick={() => setRequirementCustomFields([...requirementCustomFields, { key: '', value: '' }])}>新增字段</Button>
        </div>
      </Form>
    </Modal>
  );
}
const requirementStateMap: Record<string, string> = { DRAFT: '草稿', CONFIRMED: '已确认', IN_PROJECT: '已立项', COMPLETED: '已完成', CANCELLED: '已取消' };
const requirementStateReverse: Record<string, string> = { 草稿: 'DRAFT', 已确认: 'CONFIRMED', 已立项: 'IN_PROJECT', 已完成: 'COMPLETED', 已取消: 'CANCELLED' };

function FollowupModal({ partner, communication, open, onClose, onSaved }: Omit<Props, 'mode'>) {
  const [form] = Form.useForm<FollowupValues>();
  const [followupCustomFields, setFollowupCustomFields] = useState<CustomField[]>([]);
  useEffect(() => {
    if (!open) return;
    if (communication) {
      form.setFieldsValue({
        name: communication.name,
        time: communication.communicatedAt ? dayjs(communication.communicatedAt) : dayjs(),
        method: communication.communicationMethod,
        owner: communication.internalParticipants,
        content: communication.content,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ name: '未命名记录', time: dayjs(), method: '微信', owner: partner?.name });
    }
    setFollowupCustomFields(toCustomFieldList(communication?.customFields));
  }, [form, open, communication, partner?.name]);
  const save = async () => {
    const v = await form.validateFields();
    if (!partner) return;
    const payload: CommunicationInput = {
      partnerId: partner.id,
      name: v.name,
      communicatedAt: v.time.toISOString(),
      communicationMethod: v.method,
      internalParticipants: v.owner,
      content: v.content,
      status: (communication?.status ?? 'OPEN') as Communication['status'],
      customFields: toCustomFieldRecord(followupCustomFields),
      version: communication?.version ?? 0,
    };
    if (communication) await updateCommunication(communication.id, payload);
    else await createCommunication(payload);
    form.resetFields();
    onSaved();
    onClose();
  };
  return (
    <Modal
      className="cm-prototype-modal cm-follow-modal"
      width={820}
      open={open}
      onCancel={onClose}
      title={communication ? '编辑跟进' : '添加跟进'}
      footer={
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" icon={<SaveOutlined />} onClick={() => void save()}>
            保存
          </Button>
        </div>
      }
    >
      <Form form={form} layout="vertical">
        <Row gutter={24}>
          <Col span={6}>
            <Form.Item name="name" label="跟进名称" rules={[{ required: true }]}>
              <Input placeholder="请输入跟进名称" />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="time" label="跟进时间" rules={[{ required: true }]}>
              <DatePicker showTime format="YYYY/MM/DD HH:mm" style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="method" label="跟进方式">
              <Select options={['微信', '电话', '邮件', '会议', '现场'].map((value) => ({ value }))} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="owner" label="跟进人">
              <Input placeholder="请输入跟进人" />
            </Form.Item>
          </Col>
        </Row>
        <Form.Item name="content" label="跟进内容" rules={[{ required: true }]}>
          <Input.TextArea rows={4} placeholder="请输入跟进内容" />
        </Form.Item>
        <div className="cm-edit-divider"><span>自定义字段</span></div>
        <div className="cm-custom-fields">
          {followupCustomFields.map((field, index) => (
            <div className="cm-custom-field-row" key={index}>
              <Input
                value={field.key}
                placeholder="字段名"
                onChange={(e) => {
                  const next = [...followupCustomFields];
                  next[index] = { ...next[index]!, key: e.target.value };
                  setFollowupCustomFields(next);
                }}
              />
              <Input
                value={field.value ?? ''}
                placeholder="字段值"
                onChange={(e) => {
                  const next = [...followupCustomFields];
                  next[index] = { ...next[index]!, value: e.target.value };
                  setFollowupCustomFields(next);
                }}
              />
              <Button danger icon={<DeleteOutlined />} onClick={() => setFollowupCustomFields(followupCustomFields.filter((_, i) => i !== index))} />
            </div>
          ))}
          <Button className="cm-custom-field" icon={<PlusOutlined />} onClick={() => setFollowupCustomFields([...followupCustomFields, { key: '', value: '' }])}>新增字段</Button>
        </div>
      </Form>
    </Modal>
  );
}

function CustomFieldEditor({ fields, onChange }: CustomFieldEditorProps) {
  const update = (index: number, patch: Partial<CustomField>) =>
    onChange(fields.map((field, i) => (i === index ? { ...field, ...patch } : field)));
  return (
    <div className="cm-custom-fields">
      {fields.map((field, index) => (
        <div className="cm-custom-field-row" key={index}>
          <Input
            placeholder="字段名"
            value={field.key}
            onChange={(e) => update(index, { key: e.target.value })}
          />
          <Input
            placeholder="字段值"
            value={field.value}
            onChange={(e) => update(index, { value: e.target.value })}
          />
          <Button
            danger
            icon={<DeleteOutlined />}
            onClick={() => onChange(fields.filter((_, i) => i !== index))}
          />
        </div>
      ))}
      <Button
        className="cm-custom-field"
        icon={<PlusOutlined />}
        onClick={() => onChange([...fields, { key: '', value: '' }])}
      >
        新增字段
      </Button>
    </div>
  );
}
