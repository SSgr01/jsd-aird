import { ApartmentOutlined, DeleteOutlined, EditOutlined, MoreOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { App, Button, Dropdown, Empty, Form, Input, InputNumber, Modal, Select, Space, Spin, Table, Tag, Tree, TreeSelect, Typography } from 'antd';
import { useEffect, useMemo, useState, type ReactNode } from 'react';

import { generateAdminPassword } from '@/services/auth/password-generator';
import { iamApi, type DepartmentNode, type DepartmentUser, type IamRole } from '@/services/iam/iam-api';
import { HttpError } from '@/services/http/errors';
import { useAuthStore } from '@/stores/auth-store';
import { departmentTreeOptions } from './department-tree-options';
import './iam.css';

type DepartmentForm = { name: string; parentId: string | null; sortOrder: number };
type UserForm = { username: string; displayName: string; email?: string; phone?: string; departmentName: string; roleId: string };
type DepartmentTreeItem = { key: string; title: ReactNode; children: DepartmentTreeItem[] };

export function DepartmentTreePage() {
  const { message, modal } = App.useApp();
  const permissions = useAuthStore((state) => state.user?.permissions ?? []);
  const canCreate = permissions.includes('system.users.create') || permissions.includes('system.user.manage');
  const canUpdate = permissions.includes('system.users.update') || permissions.includes('system.user.manage');
  const canDelete = permissions.includes('system.users.delete') || permissions.includes('system.user.manage');
  const canManageUser = canUpdate;
  const [nodes, setNodes] = useState<DepartmentNode[]>([]);
  const [users, setUsers] = useState<DepartmentUser[]>([]);
  const [usersLoading, setUsersLoading] = useState(false);
  const [usersVersion, setUsersVersion] = useState(0);
  const [roles, setRoles] = useState<IamRole[]>([]);
  const [userDialog, setUserDialog] = useState<'create' | 'edit' | 'reset' | null>(null);
  const [targetUser, setTargetUser] = useState<DepartmentUser | null>(null);
  const [password, setPassword] = useState('');
  const [userSaving, setUserSaving] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [dialog, setDialog] = useState<'create' | 'edit' | null>(null);
  const [form] = Form.useForm<DepartmentForm>();
  const [userForm] = Form.useForm<UserForm>();

  const load = async () => {
    setLoading(true);
    try {
      const result = await iamApi.departments();
      setNodes(result);
      setUsersVersion((version) => version + 1);
      setSelectedId((current) => current && result.some((node) => node.id === current) ? current : result[0]?.id ?? null);
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '机构树加载失败');
    } finally { setLoading(false); }
  };

  useEffect(() => { void load(); }, []);

  useEffect(() => {
    if (!selectedId) { setUsers([]); return; }
    let active = true;
    setUsersLoading(true);
    setUsers([]);
    void iamApi.departmentUsers(selectedId).then((result) => {
      if (active) setUsers(result);
    }).catch((error) => {
      if (active) message.error(error instanceof HttpError ? error.message : '机构用户加载失败');
    }).finally(() => {
      if (active) setUsersLoading(false);
    });
    return () => { active = false; };
  }, [selectedId, usersVersion]);

  const selected = nodes.find((node) => node.id === selectedId);
  const children = nodes.filter((node) => node.parentId === selectedId);
  const parent = nodes.find((node) => node.id === selected?.parentId);
  const descendants = useMemo(() => {
    if (!selectedId) return new Set<string>();
    const result = new Set<string>([selectedId]);
    let changed = true;
    while (changed) {
      changed = false;
      nodes.forEach((node) => {
        if (node.parentId && result.has(node.parentId) && !result.has(node.id)) {
          result.add(node.id); changed = true;
        }
      });
    }
    return result;
  }, [nodes, selectedId]);

  const treeData = useMemo(() => {
    const ids = new Set(nodes.map((node) => node.id));
    const sorted = [...nodes].sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name, 'zh-CN'));
    const build = (parentId: string | null): DepartmentTreeItem[] =>
      sorted.filter((node) => parentId === null
        ? !node.parentId || !ids.has(node.parentId)
        : node.parentId === parentId).map((node) => ({
        key: node.id,
        title: <span className="iam-department-node"><span>{node.name}</span><span className="iam-department-count">{node.userCount}</span></span>,
        children: build(node.id),
      }));
    return build(null);
  }, [nodes]);
  const departmentOptions = useMemo(() => departmentTreeOptions(nodes), [nodes]);

  const openCreate = () => {
    const siblings = nodes.filter((node) => node.parentId === selectedId);
    form.setFieldsValue({ name: '', parentId: selectedId,
      sortOrder: Math.min(999999, Math.max(0, ...siblings.map((node) => node.sortOrder)) + 10) });
    setDialog('create');
  };
  const openEdit = () => {
    if (!selected) return;
    form.setFieldsValue({ name: selected.name, parentId: selected.parentId, sortOrder: selected.sortOrder });
    setDialog('edit');
  };
  const save = async (values: DepartmentForm) => {
    setSaving(true);
    try {
      const payload = { name: values.name.trim(), parentId: values.parentId ?? null, sortOrder: values.sortOrder };
      const saved = dialog === 'edit' && selected
        ? await iamApi.updateDepartment(selected.id, payload)
        : await iamApi.createDepartment(payload);
      setDialog(null);
      await load();
      setSelectedId(saved.id);
      message.success('机构已保存');
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '保存失败');
    } finally { setSaving(false); }
  };
  const remove = () => {
    if (!selected) return;
    modal.confirm({
      title: `删除“${selected.name}”？`,
      content: '存在下级、用户或业务数据引用时无法删除。',
      okText: '删除', okButtonProps: { danger: true }, cancelText: '取消',
      onOk: async () => {
        try {
          await iamApi.deleteDepartment(selected.id);
          message.success('机构已删除');
          await load();
        } catch (error) {
          message.error(error instanceof HttpError ? error.message : '删除失败');
        }
      },
    });
  };

  const openUser = async (mode: 'create' | 'edit', user: DepartmentUser | null = null) => {
    try {
      const items = await iamApi.roles();
      setRoles(items);
      setTargetUser(user);
      setPassword('');
      userForm.setFieldsValue({
        username: user?.username ?? '', displayName: user?.displayName ?? '',
        email: user?.email ?? '', phone: user?.phone ?? '',
        departmentName: selected?.name ?? '', roleId: user?.roleId ?? items[0]?.id,
      });
      setUserDialog(mode);
    } catch (error) { message.error(error instanceof HttpError ? error.message : '角色列表加载失败'); }
  };

  const saveUser = async (values: UserForm) => {
    if (!targetUser && (password.length < 6 || password.length > 200)) {
      message.error('密码长度为 6-200 位');
      return;
    }
    setUserSaving(true);
    try {
      if (targetUser) {
        await iamApi.updateUser(targetUser.id, { expectedVersion: targetUser.authVersion,
          displayName: values.displayName, email: values.email, phone: values.phone,
          departmentName: values.departmentName, roleId: values.roleId });
      } else {
        await iamApi.createUser({ ...values, password });
      }
      setUserDialog(null);
      setPassword('');
      message.success(targetUser ? '用户信息已保存' : '用户已创建');
      await load();
    } catch (error) { message.error(error instanceof HttpError ? error.message : '保存失败'); }
    finally { setUserSaving(false); }
  };

  const userAction = async (key: string, user: DepartmentUser) => {
    if (key === 'reset') { setTargetUser(user); setPassword(''); setUserDialog('reset'); return; }
    try {
      if (key === 'enable') await iamApi.enableUser(user.id, user.authVersion);
      if (key === 'disable') await iamApi.disableUser(user.id, user.authVersion);
      if (key === 'logout') await iamApi.forceLogout(user.id, user.authVersion);
      message.success('操作已完成');
      await load();
    } catch (error) { message.error(error instanceof HttpError ? error.message : '操作失败'); }
  };

  const resetPassword = async () => {
    if (!targetUser) return;
    if (password.length < 6 || password.length > 200) { message.error('密码长度为 6-200 位'); return; }
    setUserSaving(true);
    try {
      await iamApi.resetPassword(targetUser.id, password, targetUser.authVersion);
      setUserDialog(null); setPassword('');
      message.success('密码已重置');
      await load();
    } catch (error) { message.error(error instanceof HttpError ? error.message : '密码重置失败'); }
    finally { setUserSaving(false); }
  };

  return <div className="iam-page iam-department-page">
    <div className="page-heading">
      <div><Typography.Title level={2}>组织架构管理</Typography.Title></div>
      <Space><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>{canCreate && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增机构</Button>}</Space>
    </div>
    <div className="iam-department-layout">
      <section className="iam-department-tree" aria-label="机构树">
        <div className="iam-department-section-title">机构结构 <span>{nodes.length} 个节点</span></div>
        {loading ? <Spin /> : nodes.length ? <Tree key={nodes.map((node) => `${node.id}:${node.parentId}:${node.sortOrder}`).join('|')} showLine blockNode defaultExpandAll treeData={treeData} selectedKeys={selectedId ? [selectedId] : []} onSelect={(keys) => setSelectedId(String(keys[0] ?? selectedId ?? ''))} /> : <Empty description="暂无机构" />}
      </section>
      <section className="iam-department-detail" aria-label="机构详情">
        {selected ? <>
          <div className="iam-department-detail-head"><ApartmentOutlined /><Typography.Title level={4}>{selected.name}</Typography.Title></div>
          <dl className="iam-department-facts"><div><dt>上级机构</dt><dd>{parent?.name ?? '根节点'}</dd></div><div><dt>直属用户</dt><dd>{selected.userCount}</dd></div><div><dt>下级机构</dt><dd>{children.length}</dd></div><div><dt>排序号</dt><dd>{selected.sortOrder}</dd></div></dl>
          <div className="iam-department-actions">{canCreate && <Button icon={<PlusOutlined />} onClick={openCreate}>新增下级</Button>}{canUpdate && <Button icon={<EditOutlined />} onClick={openEdit}>编辑</Button>}{canDelete && <Button danger icon={<DeleteOutlined />} onClick={remove}>删除</Button>}{canCreate && <Button icon={<PlusOutlined />} onClick={() => void openUser('create')}>增加用户</Button>}</div>
          <div className="iam-department-users">
            <Typography.Text type="secondary">当前机构用户</Typography.Text>
            <Table<DepartmentUser>
              rowKey="id"
              size="small"
              loading={usersLoading}
              dataSource={users}
              pagination={false}
              locale={{ emptyText: '当前机构暂无用户' }}
              columns={[
                { title: '姓名', dataIndex: 'displayName', key: 'displayName' },
                { title: '账号', dataIndex: 'username', key: 'username' },
                { title: '角色', key: 'role', render: (_, user) => <Tag color={user.roleCode === 'SYSTEM_ADMIN' ? 'blue' : 'default'}>{user.roleName || '未分配'}</Tag> },
                { title: '状态', key: 'status', render: (_, user) => <Tag color={user.status === 'ACTIVE' ? 'success' : 'error'}>{user.status === 'ACTIVE' ? '启用' : '停用'}</Tag> },
                { title: '最后登录', dataIndex: 'lastLoginAt', key: 'lastLoginAt', render: (value?: string) => value ? new Date(value).toLocaleString('zh-CN') : '尚未登录' },
                ...(canManageUser ? [{ title: '操作', key: 'actions', width: 120, render: (_: unknown, user: DepartmentUser) => <Space><Button type="link" icon={<EditOutlined />} onClick={() => void openUser('edit', user)}>编辑</Button><Dropdown trigger={['click']} menu={{ items: [{ key: user.status === 'ACTIVE' ? 'disable' : 'enable', label: user.status === 'ACTIVE' ? '停用账号' : '启用账号', danger: user.status === 'ACTIVE' }, { key: 'reset', label: '重置密码' }, { key: 'logout', label: '强制下线' }], onClick: ({ key }) => void userAction(key, user) }}><Button type="text" icon={<MoreOutlined />} aria-label="更多操作" /></Dropdown></Space> }] : []),
              ]}
              scroll={{ x: 'max-content' }}
            />
          </div>
        </> : <Empty description="选择一个机构" />}
      </section>
    </div>
    <Modal title={dialog === 'edit' ? '编辑机构' : '新增机构'} open={dialog !== null} onCancel={() => setDialog(null)} onOk={() => void form.submit()} confirmLoading={saving} destroyOnHidden okText="保存" cancelText="取消">
      <Form form={form} layout="vertical" onFinish={(values) => void save(values)}>
        <Form.Item name="name" label="机构名称" rules={[{ required: true, whitespace: true, message: '请输入机构名称' }, { max: 160, message: '最多 160 个字符' }]}><Input autoFocus /></Form.Item>
        <Form.Item name="parentId" label="上级机构"><Select allowClear placeholder="无（根节点）" options={nodes.filter((node) => dialog !== 'edit' || !descendants.has(node.id)).map((node) => ({ label: node.name, value: node.id }))} /></Form.Item>
        <Form.Item name="sortOrder" label="排序号" rules={[{ required: true, message: '请输入排序号' }]}><InputNumber min={0} max={999999} precision={0} style={{ width: '100%' }} /></Form.Item>
      </Form>
    </Modal>
    <Modal title={userDialog === 'edit' ? '编辑用户' : '新增用户'} open={userDialog === 'create' || userDialog === 'edit'} onCancel={() => { setUserDialog(null); setPassword(''); }} onOk={() => void userForm.submit()} confirmLoading={userSaving} destroyOnHidden width={520} okText="保存" cancelText="取消">
      <Form<UserForm> form={userForm} layout="vertical" onFinish={(values) => void saveUser(values)} requiredMark={false}>
        <Form.Item name="username" label="账号" rules={[{ required: true, message: '请输入账号' }]}><Input disabled={userDialog === 'edit'} /></Form.Item>
        <Form.Item name="displayName" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}><Input /></Form.Item>
        <Form.Item name="email" label="邮箱"><Input /></Form.Item>
        <Form.Item name="phone" label="手机号"><Input /></Form.Item>
        <Form.Item name="departmentName" label="部门"><TreeSelect allowClear showSearch treeDefaultExpandAll treeNodeFilterProp="title" placeholder="请选择机构" treeData={departmentOptions} /></Form.Item>
        <Form.Item name="roleId" label="主角色" rules={[{ required: true, message: '请选择主角色' }]}><Select options={roles.map((role) => ({ value: role.id, label: role.name }))} /></Form.Item>
        {userDialog === 'create' && <div className="iam-password-item"><div className="iam-password-label">初始密码 <span>*</span></div><Input.Password autoComplete="new-password" value={password} onChange={(event) => setPassword(event.target.value)} suffix={<Button type="link" onClick={() => { try { setPassword(generateAdminPassword()); } catch (error) { message.error(error instanceof Error ? error.message : '生成密码失败'); } }}>随机生成</Button>} /></div>}
      </Form>
    </Modal>
    <Modal title="重置密码" open={userDialog === 'reset'} onCancel={() => { setUserDialog(null); setPassword(''); }} onOk={() => void resetPassword()} confirmLoading={userSaving} destroyOnHidden okText="重置" cancelText="取消">
      <Typography.Paragraph type="secondary">为「{targetUser?.username}」设置新密码，设置后旧密码立即失效。</Typography.Paragraph>
      <Input.Password autoComplete="new-password" placeholder="至少 6 位" value={password} onChange={(event) => setPassword(event.target.value)} />
    </Modal>
  </div>;
}
