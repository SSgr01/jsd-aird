import { EditOutlined, LockOutlined, MoreOutlined, PlusOutlined, ReloadOutlined, UserOutlined } from '@ant-design/icons';
import { App, Avatar, Button, Card, Col, Dropdown, Form, Input, Modal, Row, Select, Space, Statistic, Table, Tag, Typography } from 'antd';
import { forwardRef, type ReactNode, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react';

import { generateAdminPassword } from '@/services/auth/password-generator';
import { iamApi, type IamRole, type IamUser } from '@/services/iam/iam-api';
import { HttpError } from '@/services/http/errors';
import './iam.css';

interface UserForm {
  username: string;
  displayName: string;
  email?: string;
  phone?: string;
  departmentName?: string;
  roleId: string;
}

interface PasswordFieldHandle { getValue: () => string; clear: () => void; }

interface PasswordFieldProps {
  label: string;
  prefix?: ReactNode;
  onError: (message: string) => void;
}

const PasswordField = forwardRef<PasswordFieldHandle, PasswordFieldProps>(({ label, prefix, onError }, ref) => {
  const [value, setValue] = useState('');
  useImperativeHandle(ref, () => ({ getValue: () => value, clear: () => setValue('') }), [value]);
  const generate = () => {
    try { setValue(generateAdminPassword()); }
    catch (error) { onError(error instanceof Error ? error.message : '无法生成随机密码，请手动输入'); }
  };
  return <div className="iam-password-item">
    <div className="iam-password-label">{label} <span>*</span></div>
    <Input.Password prefix={prefix} placeholder="至少 12 位" autoComplete="new-password" value={value} onChange={(event) => setValue(event.target.value)} suffix={<Button type="link" onClick={generate}>随机生成</Button>} />
  </div>;
});
PasswordField.displayName = 'PasswordField';

export function UserManagementPage() {
  const { message } = App.useApp();
  const [users, setUsers] = useState<IamUser[]>([]);
  const [roles, setRoles] = useState<IamRole[]>([]);
  const [total, setTotal] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [resetOpen, setResetOpen] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [editing, setEditing] = useState<IamUser | null>(null);
  const [resetTarget, setResetTarget] = useState<IamUser | null>(null);
  const passwordInputRef = useRef<PasswordFieldHandle>(null);
  const resetPasswordInputRef = useRef<PasswordFieldHandle>(null);

  const load = async () => {
    setLoading(true);
    try {
      const [page, roleItems] = await Promise.all([iamApi.users({ keyword: keyword || undefined }), iamApi.roles()]);
      setUsers(page.items); setTotal(page.total); setRoles(roleItems);
    } catch (error) { message.error(error instanceof HttpError ? error.message : '用户列表加载失败'); }
    finally { setLoading(false); }
  };

  useEffect(() => { void load(); }, []);

  const clearPasswordInputs = () => {
    passwordInputRef.current?.clear();
    resetPasswordInputRef.current?.clear();
  };

  const openCreate = () => {
    setEditing(null);
    clearPasswordInputs();
    setModalOpen(true);
  };

  const openEdit = (user: IamUser) => {
    setEditing(user);
    clearPasswordInputs();
    setModalOpen(true);
  };

  const openReset = (user: IamUser) => {
    setResetTarget(user);
    clearPasswordInputs();
    setResetOpen(true);
  };

  const submit = async (values: UserForm) => {
    try {
      if (editing) {
        await iamApi.updateUser(editing.id, {
          displayName: values.displayName,
          email: values.email,
          phone: values.phone,
          departmentName: values.departmentName,
          roleId: values.roleId,
        });
      } else {
        const password = passwordInputRef.current?.getValue() || '';
        if (password.length < 12 || password.length > 200) {
          message.error('密码长度为 12-200 位');
          return;
        }
        await iamApi.createUser({
          username: values.username,
          displayName: values.displayName,
          email: values.email,
          phone: values.phone,
          departmentName: values.departmentName,
          roleId: values.roleId,
          password,
        });
      }
      message.success(editing ? '用户信息已保存' : '用户已创建');
      setModalOpen(false);
      clearPasswordInputs();
      await load();
    } catch (error) { message.error(error instanceof HttpError ? error.message : '保存失败'); }
  };

  const submitReset = async () => {
    if (!resetTarget) return;
    const resetPassword = resetPasswordInputRef.current?.getValue() || '';
    if (resetPassword.length < 12 || resetPassword.length > 200) {
      message.error('密码长度为 12-200 位');
      return;
    }
    setResetting(true);
    try {
      await iamApi.resetPassword(resetTarget.id, resetPassword);
      message.success('密码已重置，旧密码已失效');
      setResetOpen(false);
      clearPasswordInputs();
      await load();
    } catch (error) { message.error(error instanceof HttpError ? error.message : '密码重置失败'); }
    finally { setResetting(false); }
  };

  const action = async (key: string, user: IamUser) => {
    if (key === 'reset') { openReset(user); return; }
    try {
      if (key === 'enable') await iamApi.enableUser(user.id);
      if (key === 'disable') await iamApi.disableUser(user.id);
      if (key === 'logout') await iamApi.forceLogout(user.id);
      message.success('操作已完成'); await load();
    } catch (error) { message.error(error instanceof HttpError ? error.message : '操作失败'); }
  };

  const departments = useMemo(() => new Set(users.map((user) => user.departmentName).filter(Boolean)).size, [users]);
  const columns = [
    { title: '用户', key: 'user', render: (_: unknown, user: IamUser) => <Space><Avatar size="small" icon={<UserOutlined />} /><div><div className="iam-user-name">{user.displayName}</div><Typography.Text type="secondary">{user.username}</Typography.Text></div></Space> },
    { title: '部门', dataIndex: 'departmentName', key: 'departmentName', render: (value?: string) => value || '未设置' },
    { title: '系统角色', key: 'role', render: (_: unknown, user: IamUser) => <Tag color={user.roleCode === 'SYSTEM_ADMIN' ? 'blue' : 'default'}>{user.roleName || '未分配'}</Tag> },
    { title: '状态', key: 'status', render: (_: unknown, user: IamUser) => <Tag color={user.status === 'ACTIVE' ? 'success' : 'error'}>{user.status === 'ACTIVE' ? '启用' : '停用'}</Tag> },
    { title: '最后登录', dataIndex: 'lastLoginAt', key: 'lastLoginAt', render: (value?: string) => value ? new Date(value).toLocaleString('zh-CN') : '尚未登录' },
    { title: '操作', key: 'actions', render: (_: unknown, user: IamUser) => <Space><Button type="link" icon={<EditOutlined />} onClick={() => openEdit(user)}>编辑</Button><Dropdown trigger={['click']} menu={{ items: [{ key: user.status === 'ACTIVE' ? 'disable' : 'enable', label: user.status === 'ACTIVE' ? '停用账号' : '启用账号', danger: user.status === 'ACTIVE' }, { key: 'reset', label: '重置密码' }, { key: 'logout', label: '强制下线' }], onClick: ({ key }) => void action(key, user) }}><Button type="text" icon={<MoreOutlined />} /></Dropdown></Space> },
  ];

  return <div className="iam-page">
    <div className="page-heading"><div><Typography.Title level={2}>用户管理</Typography.Title><Typography.Text type="secondary">维护系统账号、部门归属和主角色，账号状态变化会立即影响当前会话。</Typography.Text></div><Space><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button><Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增用户</Button></Space></div>
    <Row gutter={[16, 16]}><Col xs={24} sm={12} lg={6}><Card className="iam-stat-card"><Statistic title="用户总数" value={total} prefix={<UserOutlined />} /></Card></Col><Col xs={24} sm={12} lg={6}><Card className="iam-stat-card"><Statistic title="启用账号" value={users.filter((user) => user.status === 'ACTIVE').length} /></Card></Col><Col xs={24} sm={12} lg={6}><Card className="iam-stat-card"><Statistic title="部门数量" value={departments} /></Card></Col><Col xs={24} sm={12} lg={6}><Card className="iam-stat-card"><Statistic title="系统角色" value={roles.length} /></Card></Col></Row>
    <Card className="iam-card" variant="borderless"><div className="iam-toolbar"><Input.Search allowClear value={keyword} onChange={(event) => setKeyword(event.target.value)} onSearch={() => void load()} placeholder="搜索用户名、姓名或部门" style={{ maxWidth: 360 }} /><Typography.Text type="secondary">共 {total} 个账号</Typography.Text></div><Table rowKey="id" loading={loading} columns={columns} dataSource={users} pagination={{ total, pageSize: 20 }} /></Card>
    <Modal title={editing ? '编辑用户' : '新增用户'} open={modalOpen} onCancel={() => { setModalOpen(false); clearPasswordInputs(); }} destroyOnHidden width={520} footer={<Space><Button onClick={() => { setModalOpen(false); clearPasswordInputs(); }}>取消</Button><Button type="primary" htmlType="submit" form="iam-user-form">保存</Button></Space>}>
      <Form<UserForm> id="iam-user-form" layout="vertical" initialValues={{ username: editing?.username, displayName: editing?.displayName, email: editing?.email, phone: editing?.phone, departmentName: editing?.departmentName, roleId: editing?.roleId ?? roles[0]?.id }} onFinish={(values) => void submit(values)} requiredMark={false}>
        <Form.Item name="username" label="账号" rules={[{ required: true, message: '请输入账号' }]}><Input disabled={Boolean(editing)} /></Form.Item>
        <Form.Item name="displayName" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}><Input /></Form.Item>
        <Form.Item name="email" label="邮箱"><Input /></Form.Item><Form.Item name="phone" label="手机号"><Input /></Form.Item><Form.Item name="departmentName" label="部门"><Input /></Form.Item>
        <Form.Item name="roleId" label="主角色" rules={[{ required: true, message: '请选择主角色' }]}><Select options={roles.map((role) => ({ value: role.id, label: role.name }))} /></Form.Item>
        {!editing && <PasswordField ref={passwordInputRef} label="初始密码" prefix={<LockOutlined />} onError={(text) => { void message.warning(text); }} />}
      </Form>
    </Modal>
    <Modal title="重置密码" open={resetOpen} onCancel={() => { setResetOpen(false); clearPasswordInputs(); }} destroyOnHidden width={448} footer={<Space><Button onClick={() => { setResetOpen(false); clearPasswordInputs(); }}>取消</Button><Button type="primary" loading={resetting} onClick={() => void submitReset()}>重置</Button></Space>}>
      <Typography.Paragraph type="secondary">为「{resetTarget?.username}」设置新密码，设置后旧密码立即失效。</Typography.Paragraph>
      <PasswordField ref={resetPasswordInputRef} label="新密码" onError={(text) => { void message.warning(text); }} />
    </Modal>
  </div>;
}
