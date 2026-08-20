import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import {
  App,
  Button,
  Card,
  Checkbox,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useMemo, useState } from 'react';

import {
  iamApi,
  type IamRole,
  type PermissionBinding,
  type PermissionDefinition,
} from '@/services/iam/iam-api';
import { HttpError } from '@/services/http/errors';
import { moduleDisplayLabel, riskDisplayLabel } from '@/routes/route-permissions';
import { PermissionTargetPicker } from './PermissionTargetPicker';
import './iam.css';

const scopes = [
  { value: 'ALL', label: '全部数据' },
  { value: 'SELF', label: '本人创建/负责' },
  { value: 'ASSIGNED', label: '分配给本人' },
  { value: 'PROJECT', label: '指定项目' },
  { value: 'CATEGORY', label: '指定分类' },
  { value: 'SELECTED', label: '指定对象' },
];
const effects = [
  { value: 'ALLOW', label: '允许' },
  { value: 'DENY', label: '拒绝' },
];

export function RolePermissionsPage() {
  const { message } = App.useApp();
  const [roles, setRoles] = useState<IamRole[]>([]);
  const [definitions, setDefinitions] = useState<PermissionDefinition[]>([]);
  const [activeRoleId, setActiveRoleId] = useState<string>();
  const [bindings, setBindings] = useState<Record<string, PermissionBinding>>({});
  const [version, setVersion] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const activeRole = roles.find((role) => role.id === activeRoleId);

  const load = async () => {
    setLoading(true);
    try {
      const [roleItems, permissionItems] = await Promise.all([
        iamApi.roles(),
        iamApi.definitions(),
      ]);
      setRoles(roleItems);
      setDefinitions(permissionItems);
      setActiveRoleId((current) => current || roleItems[0]?.id);
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '角色权限加载失败');
    } finally {
      setLoading(false);
    }
  };
  const loadBindings = async () => {
    if (!activeRoleId) return;
    try {
      const data = await iamApi.rolePermissions(activeRoleId);
      setVersion(data.version);
      setBindings(
        Object.fromEntries(data.bindings.map((binding) => [binding.permissionCode, binding])),
      );
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '权限配置加载失败');
    }
  };
  useEffect(() => {
    void load();
  }, []);
  useEffect(() => {
    void loadBindings();
  }, [activeRoleId]);

  const grouped = useMemo(
    () =>
      definitions.reduce<Record<string, PermissionDefinition[]>>((result, definition) => {
        (result[definition.module] ||= []).push(definition);
        return result;
      }, {}),
    [definitions],
  );
  const toggle = (definition: PermissionDefinition, checked: boolean) =>
    setBindings((current) => {
      const next = { ...current };
      if (checked)
        next[definition.code] = {
          permissionCode: definition.code,
          effect: 'ALLOW',
          scopeType: definition.defaultScope,
          targetIds: [],
        };
      else delete next[definition.code];
      return next;
    });
  const save = async () => {
    if (!activeRoleId) return;
    const invalid = Object.values(bindings).find(
      (binding) =>
        binding.effect === 'ALLOW' &&
        ['PROJECT', 'CATEGORY', 'SELECTED'].includes(binding.scopeType) &&
        binding.targetIds.length === 0,
    );
    if (invalid) {
      message.error('指定项目、指定分类和指定对象必须至少选择一个目标');
      return;
    }
    setSaving(true);
    try {
      const result = await iamApi.saveRolePermissions(
        activeRoleId,
        version,
        Object.values(bindings),
      );
      setVersion(result.version);
      message.success('角色权限已保存');
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '保存失败，请刷新后重试');
    } finally {
      setSaving(false);
    }
  };
  const createRole = () => {
    let code = '';
    let name = '';
    Modal.confirm({
      title: '新增自定义角色',
      content: (
        <Space direction="vertical" style={{ width: '100%' }}>
          <Input
            placeholder="角色编码，例如 LAB_REVIEWER"
            onChange={(event) => {
              code = event.target.value;
            }}
          />
          <Input
            placeholder="角色名称"
            onChange={(event) => {
              name = event.target.value;
            }}
          />
        </Space>
      ),
      onOk: async () => {
        if (!code.trim() || !name.trim()) {
          message.error('角色编码和角色名称不能为空');
          return Promise.reject(new Error('角色信息不完整'));
        }
        try {
          const role = await iamApi.createRole({ code: code.trim(), name: name.trim() });
          message.success('角色已创建');
          await load();
          setActiveRoleId(role.id);
        } catch (error) {
          message.error(error instanceof HttpError ? error.message : '创建失败');
          throw error;
        }
      },
    });
  };
  const renameRole = () => {
    if (!activeRole || activeRole.builtin) return;
    let name = activeRole.name;
    Modal.confirm({
      title: '重命名角色',
      content: (
        <Input
          defaultValue={name}
          onChange={(event) => {
            name = event.target.value;
          }}
        />
      ),
      onOk: async () => {
        await iamApi.renameRole(activeRole.id, name);
        await load();
        message.success('角色名称已更新');
      },
    });
  };
  const deleteRole = () => {
    if (!activeRole || activeRole.builtin) return;
    Modal.confirm({
      title: '删除自定义角色',
      content: '删除后无法恢复，且角色不能再被用户使用。',
      okType: 'danger',
      onOk: async () => {
        await iamApi.deleteRole(activeRole.id);
        await load();
        message.success('角色已删除');
      },
    });
  };

  if (loading)
    return (
      <div className="iam-loading">
        <Spin />
        <span>加载权限目录…</span>
      </div>
    );
  return (
    <div className="iam-page">
      <div className="page-heading">
        <div>
          <Typography.Title level={2}>角色权限配置</Typography.Title>
          <Typography.Text type="secondary">
            角色默认权限是用户权限的基线，高风险动作默认关闭。
          </Typography.Text>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          <Button icon={<PlusOutlined />} onClick={createRole}>
            新增角色
          </Button>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={saving}
            disabled={!activeRoleId}
            onClick={() => void save()}
          >
            保存权限
          </Button>
        </Space>
      </div>
      <Card className="iam-card" variant="borderless">
        <div className="iam-role-toolbar">
          <Tabs
            activeKey={activeRoleId}
            onChange={setActiveRoleId}
            items={roles.map((role) => ({
              key: role.id,
              label: (
                <span>
                  {role.name} {role.builtin && <Tag color="blue">内置</Tag>}
                </span>
              ),
            }))}
          />
          <Space>
            {activeRole && !activeRole.builtin && (
              <>
                <Button icon={<EditOutlined />} onClick={renameRole}>
                  重命名
                </Button>
                <Button danger icon={<DeleteOutlined />} onClick={deleteRole}>
                  删除
                </Button>
              </>
            )}
          </Space>
        </div>
        <div className="iam-permission-grid">
          {Object.entries(grouped).map(([module, items]) => (
            <section className="iam-permission-section" key={module}>
              <div className="iam-section-heading">
                <Typography.Title level={5}>{moduleDisplayLabel(module)}</Typography.Title>
                <Typography.Text type="secondary">{items.length} 项权限</Typography.Text>
              </div>
              {items.map((definition) => {
                const binding = bindings[definition.code];
                return (
                  <div className="iam-permission-row" key={definition.code}>
                    <Checkbox
                      checked={Boolean(binding)}
                      onChange={(event) => toggle(definition, event.target.checked)}
                    >
                      <span className="iam-permission-name">
                        {definition.name}{' '}
                        <Typography.Text type="secondary">({definition.code})</Typography.Text>
                      </span>
                    </Checkbox>
                    <Tag
                      color={
                        definition.risk === 'CRITICAL'
                          ? 'error'
                          : definition.risk === 'HIGH'
                            ? 'warning'
                            : 'default'
                      }
                    >
                      {riskDisplayLabel(definition.risk)}
                    </Tag>
                    <Select
                      size="small"
                      disabled={!binding}
                      value={binding?.effect || 'ALLOW'}
                      options={effects}
                      onChange={(effect) =>
                        setBindings((current) => ({
                          ...current,
                          [definition.code]: {
                            ...current[definition.code],
                            permissionCode: definition.code,
                            effect,
                            scopeType:
                              current[definition.code]?.scopeType || definition.defaultScope,
                            targetIds: current[definition.code]?.targetIds || [],
                          },
                        }))
                      }
                    />
                    <Select
                      size="small"
                      disabled={!binding || binding.effect === 'DENY'}
                      value={binding?.scopeType || definition.defaultScope}
                      options={scopes}
                      onChange={(scopeType) =>
                        setBindings((current) => ({
                          ...current,
                          [definition.code]: {
                            ...current[definition.code],
                            permissionCode: definition.code,
                            effect: current[definition.code]?.effect || 'ALLOW',
                            scopeType,
                            targetIds: current[definition.code]?.targetIds || [],
                          },
                        }))
                      }
                    />
                    {binding?.effect === 'ALLOW' &&
                      ['PROJECT', 'CATEGORY', 'SELECTED'].includes(binding.scopeType) && (
                        <PermissionTargetPicker
                          permissionCode={definition.code}
                          scopeType={binding.scopeType as 'PROJECT' | 'CATEGORY' | 'SELECTED'}
                          value={binding.targetIds}
                          onChange={(targetIds) =>
                            setBindings((current) => ({
                              ...current,
                              [definition.code]: {
                                ...current[definition.code],
                                permissionCode: definition.code,
                                effect: 'ALLOW',
                                scopeType: binding.scopeType,
                                targetIds,
                              },
                            }))
                          }
                        />
                      )}
                  </div>
                );
              })}
            </section>
          ))}
        </div>
      </Card>
    </div>
  );
}
