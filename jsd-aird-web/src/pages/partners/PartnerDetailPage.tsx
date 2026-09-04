import { AppstoreOutlined, ArrowLeftOutlined, DeleteOutlined, EditOutlined, PlusOutlined, UnorderedListOutlined, UpOutlined } from '@ant-design/icons';
import { App, Breadcrumb, Button, Empty, Input, Modal, Popconfirm, Select, Space, Spin, Tabs, Tag, Tooltip } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useBlocker, useParams } from 'react-router-dom';
import { changeContactStatus, changePartnerStatus, createContact, getContactProjectVectors, getPartner, getPartnerAudits, getPartnerContacts, updateContact, updatePartner, type ContactProjectVector, type PartnerAuditEntry } from '@/services/partners/partner-api';
import type { BusinessPartner, ContactInput, PartnerContact, PartnerInput } from '@/services/partners/partner-api';
import { deleteCommunication, getCommunications, getRequirements, updateRequirement } from '@/services/partners/crm-api';
import type { Communication, Requirement } from '@/services/partners/crm-api';
import { formatProjectStatus, getProjects, type Project } from '@/services/project/project-api';
import dayjs from '@/utils/dayjs';
import { errorMessage } from '@/services/http/errors';
import './partner-modals.css';
import './partner-pages.css';
import './partner-0730.css';
import './partner-prototype.css';
import { PartnerPrototypeModal } from './PartnerPrototypeModals';

// 合并所选项目的团队成员：手动成员 + 所选项目的成员，去重；多个项目合并
function mergeProjectMembers(manual: string[], projectIds: string[], projects: Project[]): string {
  const selected = projects.filter((p) => projectIds.includes(String(p.id)));
  const fromProjects = [...new Set(selected.flatMap((p) => p.teamMembers ?? []))];
  return [...new Set([...manual, ...fromProjects])].join(',');
}

const TIME_FORMAT = 'YYYY-MM-DD HH:mm';
const fmtTime = (t?: string) => (t ? dayjs(t).format(TIME_FORMAT) : '—');
const cooperationStatusColors: Record<string, string> = {
  合作中: 'green',
  需求沟通: 'orange',
  暂停: 'volcano',
  已结束: 'default',
  潜在客户: 'default',
};
const customerLevelColors: Record<string, string> = {
  重点客户: 'red',
  重要: 'red',
  普通客户: 'blue',
  普通: 'blue',
  潜在客户: 'default',
  潜在: 'default',
};

export function PartnerDetailPage() {
  const { id } = useParams();
  const [partner, setPartner] = useState<BusinessPartner>();
  const [loading, setLoading] = useState(true);
  const [newContactIds, setNewContactIds] = useState<string[]>([]);
  const [extFields, setExtFields] = useState<Array<{ key?: string; value?: string }>>([]);
  const [savedSnapshot, setSavedSnapshot] = useState<string>('');
  const companyFields = (p?: BusinessPartner) => JSON.stringify({
    name: p?.name, industry: p?.industry, address: p?.address, remark: p?.remark,
    customerLevel: p?.customerLevel, cooperationStatus: p?.cooperationStatus,
    mainBusiness: p?.mainBusiness, partnerCode: p?.partnerCode,
    extFields: extFields.filter((f) => f.key).map((f) => ({ key: f.key, value: f.value })),
  });
  const hasUnsavedCompanyChanges = () => partner ? companyFields(partner) !== savedSnapshot : false;
  const [requirements, setRequirements] = useState<Requirement[]>([]);
  const [communications, setCommunications] = useState<Communication[]>([]);
  const [audits, setAudits] = useState<PartnerAuditEntry[]>([]);
  const [projects, setProjects] = useState<ContactProjectVector[]>([]);
  const uniqueProjects = useMemo(() => {
    const map = new Map<string, ContactProjectVector>();
    projects.forEach((p) => { if (!map.has(p.projectId)) map.set(p.projectId, p); });
    return [...map.values()];
  }, [projects]);
  const [requirementsLoaded, setRequirementsLoaded] = useState(false);
  const [communicationsLoaded, setCommunicationsLoaded] = useState(false);
  const [requirementsLoading, setRequirementsLoading] = useState(false);
  const [communicationsLoading, setCommunicationsLoading] = useState(false);
  const [businessModal, setBusinessModal] = useState<{ mode: 'requirement' | 'followup'; edit?: Requirement | Communication }>();
  const [activeKey, setActiveKey] = useState<string>('overview');
  const [followupViewMode, setFollowupViewMode] = useState<'table' | 'card'>('card');
  const [projectOptions, setProjectOptions] = useState<{ value: string; label: string }[]>([]);
  const [allProjects, setAllProjects] = useState<Project[]>([]);
  const [collapsedContactIds, setCollapsedContactIds] = useState<Set<string>>(new Set());
  const [companySaving, setCompanySaving] = useState(false);
  const companySavingRef = useRef(false);
  const [savingContactIds, setSavingContactIds] = useState<Set<string>>(new Set());
  const savingContactRefs = useRef(new Set<string>());
  const { message: msg } = App.useApp();
  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const [base, contacts, projectVectors, reqPage, commPage, auditEntries] = await Promise.all([
        getPartner(id),
        getPartnerContacts(id),
        getContactProjectVectors(id),
        getRequirements({ partnerId: id, page: 1, size: 100 }),
        getCommunications({ partnerId: id, page: 1, size: 100 }),
        getPartnerAudits(id),
      ]);
      const value: BusinessPartner = {
        ...base,
        contacts: contacts.map((c) => ({
          ...c,
          manualTeamMembers: (c.members ?? '').split(/[,，]/).map((s) => s.trim()).filter(Boolean),
        })),
      };
      setPartner(value);
      // 负责人卡片默认折叠（新建的临时联系人保持展开）
      setCollapsedContactIds(new Set(value.contacts.filter((c) => !newContactIds.includes(c.id)).map((c) => c.id)));
      setProjects(projectVectors);
      setRequirements(reqPage.items.filter((r) => r.status !== 'CANCELLED'));
      setRequirementsLoaded(true);
      setCommunications(commPage.items);
      setCommunicationsLoaded(true);
      setAudits(auditEntries);
      setExtFields(Object.entries(base.customFields ?? {}).map(([key, value]) => ({ key, value: String(value) })));
      setSavedSnapshot(JSON.stringify({
        name: value.name, industry: value.industry, address: value.address, remark: value.remark,
        customerLevel: value.customerLevel, cooperationStatus: value.cooperationStatus,
        mainBusiness: value.mainBusiness, partnerCode: value.partnerCode,
        extFields: Object.entries(base.customFields ?? {}).map(([key, value]) => ({ key, value: String(value) })),
      }));
      const allProjects = await getProjects({});
      setProjectOptions(
        allProjects.items.map((p) => ({
          value: String(p.id),
          label: `${p.projectCode ?? ''} ${p.name}${p.owner ? `（负责人：${p.owner}）` : ''}`.trim(),
        }))
      );
      setAllProjects(allProjects.items);
      // 项目列表加载完成后，按手动成员 + 已选项目重算每个负责人的团队成员
      setPartner((prev) => prev ? {
        ...prev,
        contacts: prev.contacts.map((c) => ({
          ...c,
          members: mergeProjectMembers(c.manualTeamMembers ?? [], c.assignedProjectIds ?? [], allProjects.items),
        })),
      } : prev);
    } catch (e) {
      msg.error('加载客户详情失败');
    } finally {
      setLoading(false);
    }
  }, [id]);
  useEffect(() => { void load(); }, [load]);

  const ensureRequirements = useCallback(async () => {
    if (!id || requirementsLoaded) return;
    setRequirementsLoading(true);
    try {
      const reqPage = await getRequirements({ partnerId: id, page: 1, size: 100 });
      setRequirements(reqPage.items.filter((r) => r.status !== 'CANCELLED'));
      setRequirementsLoaded(true);
    } finally {
      setRequirementsLoading(false);
    }
  }, [id, requirementsLoaded]);
  const ensureCommunications = useCallback(async () => {
    if (!id || communicationsLoaded) return;
    setCommunicationsLoading(true);
    try {
      const commPage = await getCommunications({ partnerId: id, page: 1, size: 100 });
      setCommunications(commPage.items);
      setCommunicationsLoaded(true);
    } finally {
      setCommunicationsLoading(false);
    }
  }, [id, communicationsLoaded]);
  const ensureAudits = useCallback(async () => {
    if (!id) return;
    try {
      setAudits(await getPartnerAudits(id));
    } catch {
      msg.error('操作记录加载失败');
    }
  }, [id, msg]);
  const ensureProjects = useCallback(async () => {
    if (!id) return;
    try {
      const projectVectors = await getContactProjectVectors(id);
      setProjects(projectVectors);
    } catch { /* ignore */ }
  }, [id]);
  useEffect(() => {
    if (activeKey === 'requirements') void ensureRequirements();
    if (activeKey === 'meetings') void ensureCommunications();
    if (activeKey === 'audit') void ensureAudits();
    if (activeKey === 'projects') void ensureProjects();
  }, [activeKey, ensureRequirements, ensureCommunications, ensureAudits, ensureProjects]);
  const blocker = useBlocker(({ currentLocation, nextLocation }) =>
    activeKey === 'overview' &&
    hasUnsavedCompanyChanges() &&
    currentLocation.pathname !== nextLocation.pathname
  );
  useEffect(() => {
    if (blocker.state === 'blocked') {
      const modal = Modal.confirm({
        title: '存在未保存的修改',
        content: '公司概览有未保存的修改，是否放弃修改并离开？',
        okText: '放弃修改',
        cancelText: '继续编辑',
        okButtonProps: { type: 'default' },
        onOk: () => blocker.proceed(),
        onCancel: () => blocker.reset(),
      });
      return () => modal.destroy();
    }
  }, [blocker]);
  if (loading) return <Spin />;
  if (!partner || !id) return <Empty description="公司不存在" />;

  const tabLabelMap: Record<string, string> = {
    overview: '公司概览',
    requirements: '客户需求',
    projects: '关联项目',
    meetings: '跟进记录',
    audit: '操作记录',
  };

  const customerLevels = ['未分类', '重点客户', '普通客户', '潜在客户'];
  const cooperationStatuses = ['潜在客户', '需求沟通', '合作中', '暂停', '已结束'];
  const saveCompany = async () => {
    if (companySavingRef.current) return;
    if (!partner.name || !partner.name.trim()) {
      msg.error('请输入公司名称');
      return;
    }
    companySavingRef.current = true;
    setCompanySaving(true);
    const mergedCustomFields: Record<string, string> = Object.fromEntries(extFields.filter((f) => f.key).map((f) => [f.key, f.value ?? '']));
    const input: PartnerInput = {
      partnerCode: partner.partnerCode,
      name: partner.name,
      industry: partner.industry,
      address: partner.address,
      remark: partner.remark,
      customerLevel: partner.customerLevel,
      cooperationStatus: partner.cooperationStatus,
      mainBusiness: partner.mainBusiness,
      customFields: mergedCustomFields,
      version: partner.version,
    };
    try {
      await updatePartner(id!, input);
      setPartner((prev) => prev ? {
        ...prev,
        partnerCode: input.partnerCode,
        name: input.name,
        industry: input.industry,
        address: input.address,
        remark: input.remark,
        customerLevel: input.customerLevel,
        cooperationStatus: input.cooperationStatus,
        mainBusiness: input.mainBusiness,
        customFields: input.customFields,
        version: (prev.version ?? 0) + 1,
      } : prev);
      setSavedSnapshot(JSON.stringify({
        name: input.name, industry: input.industry, address: input.address, remark: input.remark,
        customerLevel: input.customerLevel, cooperationStatus: input.cooperationStatus,
        mainBusiness: input.mainBusiness, partnerCode: input.partnerCode,
        extFields: extFields.filter((f) => f.key).map((f) => ({ key: f.key, value: f.value })),
      }));
      msg.success('公司资料已更新');
    } catch (e) {
      msg.error(errorMessage(e, '公司资料保存失败'));
    } finally {
      companySavingRef.current = false;
      setCompanySaving(false);
    }
  };
  const addContact = () => {
    const tempId = `new-${Date.now()}`;
    const empty: PartnerContact = { id: tempId, partnerId: id!, name: '', title: '', department: '', phone: '', wechat: '', email: '', members: '', assignedProjectIds: [], manualTeamMembers: [], primaryContact: false, status: 'ACTIVE', customFields: {}, version: 0 };
    setPartner({ ...partner, contacts: [...partner.contacts, empty] });
    setNewContactIds((prev) => [...prev, tempId]);
  };
  const cancelNewContact = (tempId: string) => {
    setPartner({ ...partner, contacts: partner.contacts.filter((c) => c.id !== tempId) });
    setNewContactIds((prev) => prev.filter((cid) => cid !== tempId));
  };
  const savePersonSnapshot = async (person: PartnerContact) => {
    if (savingContactRefs.current.has(person.id)) return;
    if (!person.name || !person.name.trim()) {
      msg.error('请输入负责人姓名');
      return;
    }
    savingContactRefs.current.add(person.id);
    setSavingContactIds((prev) => new Set(prev).add(person.id));
    const input: ContactInput = { ...person, customFields: person.customFields ?? {} };
    try {
      if (newContactIds.includes(person.id)) {
        const created = await createContact(id!, input);
        setPartner((prev) => prev ? {
          ...prev,
          contacts: prev.contacts.map((c) =>
            c.id === person.id ? { ...person, id: created.id, version: created.version } : c,
          ),
        } : prev);
        setNewContactIds((prev) => prev.filter((cid) => cid !== person.id));
        msg.success('负责人已添加');
      } else {
        await updateContact(id!, person.id, { ...input, version: person.version });
        setPartner((prev) => prev ? {
          ...prev,
          contacts: prev.contacts.map((c) =>
            c.id === person.id ? { ...person, version: (person.version ?? 0) + 1 } : c,
          ),
        } : prev);
        msg.success('负责人已更新');
      }
    } catch (e) {
      msg.error(errorMessage(e, '负责人保存失败'));
    } finally {
      savingContactRefs.current.delete(person.id);
      setSavingContactIds((prev) => {
        const next = new Set(prev);
        next.delete(person.id);
        return next;
      });
    }
  };
  const toggleContactCollapsed = (contactId: string) => {
    setCollapsedContactIds((prev) => {
      const next = new Set(prev);
      if (next.has(contactId)) next.delete(contactId);
      else next.add(contactId);
      return next;
    });
  };
  const changePartnerLifecycle = () => {
    if (!partner || (!partner.allowedActions?.includes('DISABLE') && !partner.allowedActions?.includes('RESTORE'))) return;
    const restoring = partner.allowedActions.includes('RESTORE');
    Modal.confirm({ title: `${restoring ? '恢复' : '停用'}客户“${partner.name}”？`, okText: restoring ? '恢复' : '停用', cancelText: '取消', okButtonProps: { danger: !restoring }, onOk: async () => { await changePartnerStatus(partner.id, restoring ? 'ACTIVE' : 'INACTIVE', partner.version); msg.success(restoring ? '客户已恢复' : '客户已停用'); await load(); } });
  };
  const requirementStateName = { DRAFT: '草稿', CONFIRMED: '已确认', IN_PROJECT: '已立项', COMPLETED: '已完成', CANCELLED: '已取消' } as const;
  // 负责项目选项调用 project 接口获取全量项目列表（见 load 中的 getProjects）
  const overview = <div className="cm-customer-modal cm-modal-columns cm-customer-columns">
    <section className="cm-modal-panel">
      <div className="cm-panel-head">
        <div>
          <h3>公司资料</h3>
          <p>修改公司字段后单独保存。</p>
        </div>
        <Button size="small" type="primary" icon={<EditOutlined />} loading={companySaving} onClick={() => void saveCompany()}>保存</Button>
      </div>
      <label className="cm-block-field"><span className="cm-required">公司名称</span><Input value={partner.name ?? ''} status={!partner.name ? 'error' : undefined} maxLength={20} showCount placeholder="请输入公司名称" onChange={(e) => setPartner({ ...partner, name: e.target.value })} /></label>
      <div className="cm-form-grid">
        <label>所属行业<Input value={partner.industry ?? ''} onChange={(e) => setPartner({ ...partner, industry: e.target.value })} /></label>
        <label>客户等级<Select value={partner.customerLevel ?? '未分类'} options={customerLevels.map((value) => ({ value }))} onChange={(value) => setPartner({ ...partner, customerLevel: value })} /></label>
        <label>合作状态<Select value={partner.cooperationStatus ?? '潜在客户'} options={cooperationStatuses.map((value) => ({ value }))} onChange={(value) => setPartner({ ...partner, cooperationStatus: value })} /></label>
        <label>所在地区<Input value={partner.address ?? ''} maxLength={30} showCount onChange={(e) => setPartner({ ...partner, address: e.target.value })} /></label>
      </div>
      <label className="cm-block-field">主营业务<Input value={partner.mainBusiness ?? ''} maxLength={200} showCount onChange={(e) => setPartner({ ...partner, mainBusiness: e.target.value })} /></label>
      <div className="cm-edit-divider"><span>拓展字段</span></div>
      <div className="cm-custom-fields">
        {extFields.map((field, index) => (
          <div className="cm-custom-field-row" key={`ext-${index}`}>
            <Input value={field.key} placeholder="字段名" onChange={(e) => { const next = [...extFields]; next[index] = { ...next[index], key: e.target.value }; setExtFields(next); }} />
            <Input value={field.value ?? ''} placeholder="值" onChange={(e) => { const next = [...extFields]; next[index] = { ...next[index], value: e.target.value }; setExtFields(next); }} />
            <Button danger icon={<DeleteOutlined />} onClick={() => setExtFields(extFields.filter((_, i) => i !== index))} />
          </div>
        ))}
        <Button className="cm-custom-field" icon={<PlusOutlined />} onClick={() => setExtFields([...extFields, { key: '', value: '' }])}>新增字段</Button>
      </div>
    </section>
    <section className="cm-modal-panel">
      <div className="cm-panel-head">
        <div>
          <h3>负责人</h3>
          <p>维护负责人资料，并从项目列表选择负责项目。</p>
        </div>
        <Space size={6}>
          <Button type="primary" icon={<PlusOutlined />} onClick={addContact}>新增负责人</Button>
        </Space>
      </div>
      {partner.contacts.length === 0 && <div className="cm-related-empty">暂无负责人</div>}
      {partner.contacts.map((person) => {
        const isNew = newContactIds.includes(person.id);
        const setField = (key: keyof PartnerContact, value: unknown) => {
          setPartner({ ...partner, contacts: partner.contacts.map((c) => (c.id === person.id ? { ...c, [key]: value } : c)) });
        };
        return (
          <div className="cm-repeat-card cm-repeat-card-editing cm-repeat-card-open" key={person.id}>
            <div className="cm-repeat-title">
              <div className="cm-repeat-title-text" onClick={() => toggleContactCollapsed(person.id)}>
                <strong>{person.name || '新负责人'}</strong>
                <span className="cm-repeat-subtitle"> 已选 {(person.assignedProjectIds ?? []).length} 个项目</span>
                <UpOutlined className={`cm-repeat-chevron ${collapsedContactIds.has(person.id) ? 'collapsed' : ''}`} />
              </div>
            </div>
            {!collapsedContactIds.has(person.id) && (
            <>
            <div className="cm-form-grid cm-form-grid-3">
              <label><span className="cm-required">姓名</span><Input value={person.name ?? ''} status={!person.name ? 'error' : undefined} onChange={(e) => setField('name', e.target.value)} placeholder="请输入姓名" /></label>
              <label>职位<Input value={person.title ?? ''} onChange={(e) => setField('title', e.target.value)} /></label>
              <label>部门<Input value={person.department ?? ''} onChange={(e) => setField('department', e.target.value)} /></label>
            </div>
            <div className="cm-form-grid cm-form-grid-3">
              <label>手机<Input value={person.phone ?? ''} onChange={(e) => setField('phone', e.target.value)} /></label>
              <label>微信<Input value={person.wechat ?? ''} onChange={(e) => setField('wechat', e.target.value)} /></label>
              <label>邮箱<Input value={person.email ?? ''} onChange={(e) => setField('email', e.target.value)} /></label>
            </div>
            <label>团队成员
              <Select
                mode="tags"
                placeholder="输入姓名后按回车"
                tokenSeparators={[',', '，', '\n']}
                style={{ width: '100%' }}
                allowClear
                value={person.manualTeamMembers ?? []}
                onInputKeyDown={(event) => {
                  if (event.key !== 'Enter') return;
                  const candidate = event.currentTarget.value.trim();
                  if (candidate && (person.manualTeamMembers ?? []).some((member) => member.trim() === candidate)) {
                    event.preventDefault();
                    event.stopPropagation();
                  }
                }}
                onChange={(value) => {
                  const manual = [...new Set(((value as string[]) ?? []).map((item) => item.trim()).filter(Boolean))];
                  const members = mergeProjectMembers(manual, person.assignedProjectIds ?? [], allProjects);
                  setPartner({ ...partner, contacts: partner.contacts.map((c) => c.id === person.id ? { ...c, manualTeamMembers: manual, members } : c) });
                }}
              />
            </label>
            <label>负责项目（可多选）
              <Select mode="multiple" allowClear showSearch optionFilterProp="label" value={person.assignedProjectIds ?? []} options={projectOptions}
                onChange={(value) => {
                  const ids = (value as string[]) ?? [];
                  const fromProjects = [...new Set(allProjects.filter((p) => ids.includes(String(p.id))).flatMap((p) => p.teamMembers ?? []))];
                  const manual = [...new Set([...(person.manualTeamMembers ?? []), ...fromProjects])];
                  const members = mergeProjectMembers(manual, ids, allProjects);
                  setPartner({ ...partner, contacts: partner.contacts.map((c) => c.id === person.id ? { ...c, assignedProjectIds: ids, manualTeamMembers: manual, members } : c) });
                }}
              />
            </label>
            <div className="cm-edit-divider"><span>拓展字段</span></div>
            <div className="cm-custom-fields">
              {Object.entries(person.customFields ?? {}).map(([key, value]) => (
                <div className="cm-custom-field-row" key={`p-ext-${key}`}>
                  <Input value={key} placeholder="字段名" onChange={(e) => { const next: Record<string, string> = { ...(person.customFields as Record<string, string> ?? {}) }; delete next[key]; next[e.target.value] = String(value ?? ''); setField('customFields', next); }} />
                  <Input value={String(value ?? '')} placeholder="值" onChange={(e) => { const next: Record<string, string> = { ...(person.customFields as Record<string, string> ?? {}) }; next[key] = e.target.value; setField('customFields', next); }} />
                  <Button danger icon={<DeleteOutlined />} onClick={() => { const next: Record<string, string> = { ...(person.customFields as Record<string, string> ?? {}) }; delete next[key]; setField('customFields', next); }} />
                </div>
              ))}
              <Button className="cm-custom-field" icon={<PlusOutlined />} onClick={() => { const next: Record<string, string> = { ...(person.customFields as Record<string, string> ?? {}) }; next[`字段${Object.keys(next).length + 1}`] = ''; setField('customFields', next); }}>新增字段</Button>
            </div>
            </>
            )}
            <div className="cm-repeat-footer">
              <Space size={8}>
                {isNew
                  ? <Button danger type="primary" icon={<DeleteOutlined />} onClick={() => cancelNewContact(person.id)}>删除</Button>
                  : <Popconfirm title={person.allowedActions?.includes('RESTORE') ? '恢复该负责人？' : '停用该负责人？'} description="历史业务关联将继续保留；客户至少保留一位负责人。" onConfirm={() => void (async () => {
                    const activeContactCount = partner.contacts.filter((contact) => contact.status === 'ACTIVE').length;
                    if (!person.allowedActions?.includes('RESTORE') && activeContactCount <= 1) {
                      msg.warning('至少保留一位负责人，当前负责人不可停用');
                      return;
                    }
                    await changeContactStatus(id, person);
                    msg.success(person.allowedActions?.includes('RESTORE') ? '负责人已恢复' : '负责人已停用');
                    await load();
                  })()}>
                    <Button danger={!person.allowedActions?.includes('RESTORE')} type="primary" icon={<DeleteOutlined />} disabled={!person.allowedActions?.includes('DISABLE') && !person.allowedActions?.includes('RESTORE')} onClick={(e) => e.stopPropagation()}>{person.allowedActions?.includes('RESTORE') ? '恢复' : '停用'}</Button>
                  </Popconfirm>}
                <Button type="primary" icon={<EditOutlined />} loading={savingContactIds.has(person.id)} disabled={(!isNew && person.status === 'INACTIVE') || savingContactIds.has(person.id)} onClick={() => void savePersonSnapshot(person)}>保存</Button>
              </Space>
            </div>
          </div>
        );
      })}
    </section>
  </div>;

  return <div className="cm-page">
    <Breadcrumb items={[{ title: '客户管理' }, { title: partner.name }, { title: tabLabelMap[activeKey] ?? '公司概览' }]} />
    <div className="cm-detail-head"><div className="cm-back-title"><Link className="cm-back" to="/partners"><ArrowLeftOutlined /></Link><div className="cm-detail-title"><h3>{partner.name} {partner.customerLevel && <Tag color={customerLevelColors[partner.customerLevel] ?? 'default'}>{partner.customerLevel}</Tag>}<Tag color={cooperationStatusColors[partner.cooperationStatus ?? ''] ?? 'default'}>{partner.cooperationStatus || '潜在客户'}</Tag></h3><div className="cm-detail-meta"><span>客户编号：{partner.partnerCode}</span><span>所属行业：{partner.industry || '—'}</span><span>客户等级：{partner.customerLevel || '—'}</span></div></div></div><div style={{ marginLeft: 'auto' }}>{partner.allowedActions?.includes('DISABLE') || partner.allowedActions?.includes('RESTORE') ? <Button danger={partner.allowedActions?.includes('DISABLE')} onClick={changePartnerLifecycle}>{partner.allowedActions?.includes('RESTORE') ? '恢复' : '停用'}</Button> : null}</div></div>
    <div className="cm-tabs-shell"><Tabs className="cm-tabs" activeKey={activeKey} onChange={setActiveKey} items={[
      { key: 'overview', label: '公司概览', children: <div className="cm-tab-body">{overview}</div> },
      {
        key: 'requirements',
        label: '客户需求',
        children: (
          <div className="cm-tab-body">
            <div className="cm-section-title">
              <h5>客户需求 <small>{requirements.length} 条</small></h5>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setBusinessModal({ mode: 'requirement' })}>新建客户需求</Button>
            </div>
            <div className="cm-table-card" style={{ marginTop: 12 }} aria-busy={requirementsLoading}>
              {requirementsLoading ? (
                <div className="cm-table-loading"><Spin /></div>
              ) : (
                <div className="cm-table-wrap">
                  <table className="cm-table cm-requirement-list">
                    <thead>
                      <tr><th>需求编号</th><th>需求名称</th><th>关联项目</th><th>提出日期</th><th>预计完成日期</th><th>紧急程度</th><th>状态</th><th>操作</th></tr>
                    </thead>
                    <tbody>
                      {requirements.map((row) => (
                        <tr key={row.id}>
                          <td>{row.requirementCode}</td>
                          <td>{row.title}</td>
                          <td>{(() => {
                            const ids = row.projectIds?.length ? row.projectIds : row.projectId ? [row.projectId] : [];
                            const links = ids.map((projectId) => {
                              const project = allProjects.find((p) => String(p.id) === projectId);
                              return project ? <Link key={projectId} to={`/projects/${projectId}`}>{project.name}</Link> : null;
                            }).filter(Boolean);
                            return links.length ? links.map((link, index) => <span key={index}>{index > 0 && '、'}{link}</span>) : '—';
                          })()}</td>
                          <td>{row.raisedAt || '—'}</td>
                          <td>{row.deliveryDate || '—'}</td>
                          <td>{row.urgency || '—'}</td>
                          <td><Tag>{row.customStatusName || requirementStateName[row.status]}</Tag></td>
                          <td>
                            <div className="cm-row-actions">
                              <button className="cm-link-button" onClick={() => setBusinessModal({ mode: 'requirement', edit: row })}>编辑</button>
                              <Popconfirm title="取消该客户需求？" description="历史业务关联将继续保留。" onConfirm={() => void (async () => { await updateRequirement(row.id, { ...row, status: 'CANCELLED', version: row.version }); msg.success('客户需求已取消'); await load(); })()}>
                                <Button danger type="text" disabled={row.status === 'CANCELLED'} onClick={(e) => e.stopPropagation()}>删除</Button>
                              </Popconfirm>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              {!requirementsLoading && !requirements.length && <Empty className="cm-empty" description="暂无客户需求" />}
            </div>
          </div>
        ),
      },
      {
        key: 'projects',
        label: '关联项目',
        children: (
          <div className="cm-tab-body">
            <div className="cm-section-title">
              <h5>关联项目 <small>{uniqueProjects.length} 个</small></h5>
            </div>
            <div className="cm-table-card" style={{ marginTop: 12 }}>
              <div className="cm-table-wrap">
                <table className="cm-table cm-project-list">
                  <thead>
                    <tr><th>项目编号</th><th>项目名称</th><th>项目负责人</th><th>当前阶段</th><th>状态</th><th>进度</th><th>操作</th></tr>
                  </thead>
                  <tbody>
                    {uniqueProjects.map((row) => (
                      <tr key={row.projectId}>
                        <td>{row.projectCode}</td>
                        <td>{row.projectName}</td>
                        <td>{row.projectOwner || '—'}</td>
                        <td>{row.currentStageName || '—'}</td>
                        <td>
                          <Tag color={row.projectStatus === 'IN_PROGRESS' ? 'green' : 'default'}>
                            {formatProjectStatus(row.projectStatus)}
                          </Tag>
                        </td>
                        <td>{row.progress ?? 0}%</td>
                        <td>
                          <Link to={`/projects/${row.projectId}`}>
                            <Button type="link" size="small">查看</Button>
                          </Link>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {!uniqueProjects.length && <Empty className="cm-empty" description="暂无关联项目" />}
            </div>
          </div>
        ),
      },
      {
        key: 'meetings',
        label: '跟进记录',
        children: (
          <div className="cm-tab-body">
            <div className="cm-section-title">
              <h5>跟进记录 <small>{communications.length} 条</small></h5>
              <Space size={8}>
                <Button type="primary" icon={<PlusOutlined />} onClick={() => setBusinessModal({ mode: 'followup' })}>新建跟进记录</Button>
                <Space.Compact>
                  <Tooltip title="竖版视图">
                    <Button
                      className={followupViewMode === 'card' ? 'cm-view-btn active' : 'cm-view-btn'}
                      icon={<AppstoreOutlined />}
                      onClick={() => setFollowupViewMode('card')}
                    />
                  </Tooltip>
                  <Tooltip title="表格视图">
                    <Button
                      className={followupViewMode === 'table' ? 'cm-view-btn active' : 'cm-view-btn'}
                      icon={<UnorderedListOutlined />}
                      onClick={() => setFollowupViewMode('table')}
                    />
                  </Tooltip>
                </Space.Compact>
              </Space>
            </div>
            <div className="cm-table-card" style={{ marginTop: 12 }} aria-busy={communicationsLoading}>
              {communicationsLoading ? (
                <div className="cm-table-loading"><Spin /></div>
              ) : followupViewMode === 'table' ? (
                <div className="cm-table-wrap">
                  <table className="cm-table cm-followup-list">
                    <thead>
                      <tr><th>跟进编号</th><th>跟进名称</th><th>跟进时间</th><th>跟进方式</th><th>跟进人</th><th>操作</th></tr>
                    </thead>
                    <tbody>
                      {communications.map((row) => (
                        <tr key={row.id}>
                          <td>{row.recordCode}</td>
                          <td>{row.name}</td>
                          <td>{fmtTime(row.communicatedAt)}</td>
                          <td>{row.communicationMethod || '—'}</td>
                          <td>{row.internalParticipants || '—'}</td>
                          <td>
                            <div className="cm-row-actions">
                              <button className="cm-link-button" onClick={() => setBusinessModal({ mode: 'followup', edit: row })}>编辑</button>
                              {row.allowedActions?.includes('DELETE') && <Popconfirm title="删除该跟进记录？" description="历史业务关联将继续保留。" onConfirm={() => void (async () => { await deleteCommunication(row.id, row.version); msg.success('跟进记录已删除'); await load(); })()}>
                                <Button danger type="text" onClick={(e) => e.stopPropagation()}>删除</Button>
                              </Popconfirm>}
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div className="cm-followup-timeline">
                  {[...communications]
                    .sort((a, b) => String(b.communicatedAt).localeCompare(String(a.communicatedAt)))
                    .map((row) => (
                      <div className="cm-timeline-item" key={row.id}>
                        <div className="cm-timeline-marker">
                          <span className="cm-timeline-dot" />
                          <span className="cm-timeline-line" />
                        </div>
                        <div className="cm-timeline-content">
                          <div className="cm-timeline-head">
                            <span className="cm-timeline-time">{fmtTime(row.communicatedAt)}</span>
                          </div>
                          <div className="cm-timeline-title">
                            <span className="cm-timeline-code">{row.recordCode}</span>
                            <span className="cm-timeline-name">{row.name}</span>
                          </div>
                          <div className="cm-timeline-meta">
                            <span className="cm-timeline-method"><span className="cm-timeline-label">跟进方式</span>{row.communicationMethod || '—'}</span>
                            <span className="cm-timeline-owner"><span className="cm-timeline-label">跟进人</span>{row.internalParticipants || '—'}</span>
                          </div>
                          {row.content && <div className="cm-timeline-text">{row.content}</div>}
                          <div className="cm-timeline-actions">
                            <button className="cm-link-button" onClick={() => setBusinessModal({ mode: 'followup', edit: row })}>编辑</button>
                            {row.allowedActions?.includes('DELETE') && <Popconfirm title="删除该跟进记录？" description="历史业务关联将继续保留。" onConfirm={() => void (async () => { await deleteCommunication(row.id, row.version); msg.success('跟进记录已删除'); await load(); })()}>
                              <Button danger type="text" size="small">删除</Button>
                            </Popconfirm>}
                          </div>
                        </div>
                      </div>
                    ))}
                </div>
              )}
              {!communicationsLoading && !communications.length && <Empty className="cm-empty" description="暂无跟进记录" />}
            </div>
          </div>
        ),
      },
      {
        key: 'audit',
        label: '操作记录',
        children: (
          <div className="cm-tab-body">
            <div className="cm-section-title">
              <h5>操作记录 <small>{audits.length} 条</small></h5>
            </div>
            <div className="cm-table-card" style={{ marginTop: 12 }}>
              {audits.length ? (
                <div className="cm-table-wrap">
                  <table className="cm-table cm-audit-list">
                    <thead><tr><th>时间</th><th>操作人</th><th>动作</th><th>对象</th><th>结果详情</th></tr></thead>
                    <tbody>{audits.map((entry) => (
                      <tr key={entry.id}>
                        <td>{fmtTime(entry.createdAt)}</td>
                        <td>{String(entry.detail?.operator ?? entry.actorId ?? '—')}</td>
                        <td><Tag color="blue">{entry.action}</Tag></td>
                        <td>{entry.aggregateType} / {entry.aggregateId}</td>
                        <td>{entry.detail?.detail ? String(entry.detail.detail) : '已记录'}</td>
                      </tr>
                    ))}</tbody>
                  </table>
                </div>
              ) : <Empty className="cm-empty" description="暂无操作记录" />}
            </div>
          </div>
        ),
      },
    ]} /></div>
    {businessModal && <PartnerPrototypeModal mode={businessModal.mode} partner={partner} requirement={businessModal.edit instanceof Object && 'requirementCode' in businessModal.edit ? (businessModal.edit as Requirement) : undefined} communication={businessModal.edit instanceof Object && 'recordCode' in businessModal.edit ? (businessModal.edit as Communication) : undefined} open onClose={() => setBusinessModal(undefined)} onSaved={async () => { if (businessModal.mode === 'requirement') { setRequirementsLoaded(false); await ensureRequirements(); } else { setCommunicationsLoaded(false); await ensureCommunications(); } }} />}
  </div>;
}
