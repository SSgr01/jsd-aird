import { FormModal } from '@/components/form-dialog/FormModal';
import { DeleteOutlined, PlusOutlined, UpOutlined } from '@ant-design/icons';
import { App, Button, Col, DatePicker, Form, Input, Row, Select, Space } from 'antd';
import dayjs from '@/utils/dayjs';
import type { Dayjs } from 'dayjs';
import { useEffect, useRef, useState } from 'react';
import { createCommunication, updateCommunication } from '@/services/partners/crm-api';
import type { Communication, CommunicationInput } from '@/services/partners/crm-api';
import { getProjects } from '@/services/project/project-api';
import type { Project } from '@/services/project/project-api';
import {
  createContact,
  createPartner,
  deleteContact,
  getPartnerContacts,
  updateContact,
  updatePartner,
} from '@/services/partners/partner-api';
import type {
  BusinessPartner,
  PartnerContact,
} from '@/services/partners/partner-api';
import { errorMessage } from '@/services/http/errors';
import { useAuthStore } from '@/stores/auth-store';
import './partner-modals.css';

type Props = {
  mode?: 'customer' | 'followup';
  partner?: BusinessPartner;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
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
  expanded: true,
  teamMembers: [],
  manualTeamMembers: [],
  projectIds: [],
});

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
  partnerCode?: string;
  name: string;
  industry?: string;
  level?: string;
  cooperation?: string;
  region?: string;
  business?: string;
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
  communication,
}: Props) {
  if (mode === 'followup')
    return <FollowupModal partner={partner} communication={communication} open={open} onClose={onClose} onSaved={onSaved} />;
  return <CustomerModal partner={partner} open={open} onClose={onClose} onSaved={onSaved} />;
}

function CustomerModal({ partner, open, onClose, onSaved }: Omit<Props, 'mode'>) {
  const { message } = App.useApp();
  const canCreateContact = useAuthStore((state) => state.can('customer.overview.create'));
  const [form] = Form.useForm<CustomerValues>();
  const [people, setPeople] = useState<Person[]>([]);
  const [nameTouched, setNameTouched] = useState(false);
  const [partnerFields, setPartnerFields] = useState<CustomField[]>([]);
  const [loadedContacts, setLoadedContacts] = useState<PartnerContact[]>([]);
  const [projectOptions, setProjectOptions] = useState<{ value: string; label: string }[]>([]);
  const [allProjects, setAllProjects] = useState<Project[]>([]);
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);

  useEffect(() => {
    if (!open) return;
    form.setFieldsValue(
      partner
        ? {
            partnerCode: partner.partnerCode,
            name: partner.name,
            industry: partner.industry,
            region: partner.address,
            business: partner.mainBusiness,
            level: partner.customerLevel,
            cooperation: partner.cooperationStatus,
          }
        : { partnerCode: undefined, name: undefined, industry: undefined, region: undefined, business: undefined },
    );
    setPeople([]);
    setNameTouched(false);
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

  const updatePerson = (index: number, patch: Partial<Person>) =>
    setPeople((prev) => prev.map((p, i) => (i === index ? { ...p, ...patch } : p)));

  const save = async () => {
    if (savingRef.current) return;
    savingRef.current = true;
    setSaving(true);
    let createdPartnerId: string | undefined;
    try {
      const v = await form.validateFields();
      if (people.length > 0 && people.some((p) => !p.name || !p.name.trim())) {
        setNameTouched(true);
        message.error('请输入负责人姓名');
        return;
      }
      if (!partner && people.some((person) => person.name) && !canCreateContact) {
        message.error('当前用户没有新增负责人权限，请移除负责人后再保存客户');
        return;
      }
      const input = {
        partnerCode: v.partnerCode?.trim() || partner?.partnerCode,
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
      const partnerId = partner?.id ?? (createdPartnerId = (await createPartner(input)).id);
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
            members: (person.manualTeamMembers ?? []).join(','),
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
          await deleteContact(partner.id, contact.id, contact.version);
      form.resetFields();
      setPeople([]);
    setNameTouched(false);
      setLoadedContacts([]);
      setProjectOptions([]);
      setAllProjects([]);
      setPartnerFields([]);
      message.success(partner ? '客户已更新' : '客户已创建');
      onSaved();
      onClose();
    } catch (e) {
      message.error(createdPartnerId
        ? `客户已创建，但负责人保存失败：${errorMessage(e, '请重试')}`
        : errorMessage(e, '客户保存失败'));
      if (createdPartnerId) onSaved();
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  };

  return (
    <FormModal form={form}
      className="cm-prototype-modal cm-customer-modal"
      width={1200}
      open={open}
      onCancel={onClose}
      title={partner ? '编辑客户' : '新建客户'}
      onOk={() => save()} confirmLoading={saving} okText="保存" cancelText="取消"
    >
      <div className="cm-modal-columns cm-customer-columns">
        <section className="cm-modal-panel">
          <h3>公司资料</h3>
          <p>填写客户公司的基础资料。</p>
          <Form form={form} layout="vertical">
            <div className="cm-form-grid">
              <Form.Item
                name="partnerCode"
                label="客户编号"
                extra={partner ? undefined : '留空时由系统自动生成，例如 CUS-001'}
              >
                <Input placeholder="例如 CUS-001" maxLength={32} />
              </Form.Item>
              <Form.Item name="name" label="公司名称" rules={[{ required: true }, { max: 20, message: '公司名称不能超过20个字' }]}>
                <Input placeholder="请输入公司名称" maxLength={20} showCount />
              </Form.Item>
            </div>
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
              <Form.Item name="region" label="所在地区" rules={[{ max: 30, message: '所在地区不能超过30个字' }]}>
                <Input placeholder="请输入所在地区" maxLength={30} showCount />
              </Form.Item>
            </div>
            <Form.Item name="business" label="主营业务" rules={[{ max: 200, message: '主营业务不能超过200个字' }]}>
              <Input.TextArea rows={3} placeholder="请输入主营业务" maxLength={200} showCount />
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
            <Space size={6}>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setPeople([...people, emptyPerson()])}>
                新增负责人
              </Button>
            </Space>
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
                      <span className="cm-required">姓名</span>
                      <Input
                        value={person.name}
                        status={!person.name && nameTouched ? 'error' : undefined}
                        placeholder="请输入姓名"
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
                    <Select
                      mode="tags"
                      placeholder="输入姓名后按回车"
                      tokenSeparators={[',', '，', '\n']}
                      style={{ width: '100%' }}
                      allowClear
                      value={person.manualTeamMembers ?? []}
                      onChange={(value) => {
                        const manual = (value as string[]) ?? [];
                        updatePerson(index, { manualTeamMembers: manual });
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
                        const fromProjects = [...new Set(allProjects.filter((p) => ids.includes(String(p.id))).flatMap((p) => p.teamMembers ?? []))];
                        const manual = [...new Set([...(person.manualTeamMembers ?? []), ...fromProjects])];
                        updatePerson(index, {
                          projectIds: ids,
                          manualTeamMembers: manual,
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
    </FormModal>
  );
}

function FollowupModal({ partner, communication, open, onClose, onSaved }: Omit<Props, 'mode'>) {
  const { message } = App.useApp();
  const [form] = Form.useForm<FollowupValues>();
  const [followupCustomFields, setFollowupCustomFields] = useState<CustomField[]>([]);
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
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
      form.setFieldsValue({ time: dayjs(), method: '微信', owner: partner?.name });
    }
    setFollowupCustomFields(toCustomFieldList(communication?.customFields));
  }, [form, open, communication, partner?.name]);
  const save = async () => {
    if (savingRef.current) return;
    savingRef.current = true;
    setSaving(true);
    try {
      const v = await form.validateFields();
      if (!partner) return;
      const payload: CommunicationInput = {
        partnerId: partner.id,
        name: v.name.trim(),
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
      message.success(communication ? '跟进记录已更新' : '跟进记录已创建');
      onSaved();
      onClose();
    } catch (e) {
      message.error(errorMessage(e, '跟进记录保存失败'));
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  };
  return (
    <FormModal form={form}
      className="cm-prototype-modal cm-follow-modal"
      width={820}
      open={open}
      onCancel={onClose}
      title={communication ? '编辑跟进' : '添加跟进'}
      onOk={() => save()} confirmLoading={saving} okText="保存" cancelText="取消"
    >
      <Form form={form} layout="vertical">
        <Row className="cm-form-row" gutter={24}>
          <Col span={6}>
            <Form.Item name="name" label="跟进名称" rules={[{ required: true, whitespace: true, message: '请输入跟进名称' }]}>
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
              <Select options={['微信', '电话', '邮件', '会议', '现场拜访', '样品寄送'].map((value) => ({ value }))} />
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
    </FormModal>
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
