import { ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { App, Button, Card, Checkbox, Select, Space, Spin, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';

import {
  iamApi,
  type IamUser,
  type PermissionBinding,
  type PermissionDefinition,
} from '@/services/iam/iam-api';
import { HttpError } from '@/services/http/errors';
import { moduleDisplayLabel } from '@/routes/route-permissions';
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

export function UserPermissionsPage() {
  const { message } = App.useApp();
  const [users, setUsers] = useState<IamUser[]>([]);
  const [definitions, setDefinitions] = useState<PermissionDefinition[]>([]);
  const [activeUserId, setActiveUserId] = useState<string>();
  const [bindings, setBindings] = useState<Record<string, PermissionBinding>>({});
  const [version, setVersion] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const activeUser = users.find((user) => user.id === activeUserId);
  const load = async () => {
    setLoading(true);
    try {
      const [userPage, permissionItems] = await Promise.all([
        iamApi.users({ size: 100 }),
        iamApi.definitions(),
      ]);
      setUsers(userPage.items);
      setDefinitions(permissionItems);
      setActiveUserId((current) => current || userPage.items[0]?.id);
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '用户权限加载失败');
    } finally {
      setLoading(false);
    }
  };
  const loadBindings = async () => {
    if (!activeUserId) return;
    try {
      const data = await iamApi.userPermissions(activeUserId);
      setVersion(data.version);
      setBindings(
        Object.fromEntries(data.bindings.map((binding) => [binding.permissionCode, binding])),
      );
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '个人覆盖加载失败');
    }
  };
  useEffect(() => {
    void load();
  }, []);
  useEffect(() => {
    void loadBindings();
  }, [activeUserId]);
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
    if (!activeUserId) return;
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
      const result = await iamApi.saveUserPermissions(
        activeUserId,
        version,
        Object.values(bindings),
      );
      setVersion(result.version);
      message.success('个人权限覆盖已保存');
    } catch (error) {
      message.error(
        error instanceof HttpError ? error.message : '保存失败，可能已被其他管理员修改',
      );
    } finally {
      setSaving(false);
    }
  };
  const restoreDefaults = async () => {
    if (!activeUserId) return;
    setSaving(true);
    try {
      const result = await iamApi.saveUserPermissions(activeUserId, version, []);
      setBindings({});
      setVersion(result.version);
      message.success('已恢复角色默认权限');
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '恢复角色默认权限失败');
    } finally {
      setSaving(false);
    }
  };
  if (loading)
    return (
      <div className="iam-loading">
        <Spin />
        <span>加载用户权限…</span>
      </div>
    );
  return (
    <div className="iam-page">
      <div className="page-heading">
        <div>
          <Typography.Title level={2}>用户权限配置</Typography.Title>
          <Typography.Text type="secondary">
            个人覆盖会替换角色基线；未设置覆盖时继承主角色权限。
          </Typography.Text>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={saving}
            disabled={!activeUserId}
            onClick={() => void save()}
          >
            保存覆盖
          </Button>
          <Button disabled={!activeUserId || saving} onClick={() => void restoreDefaults()}>
            恢复角色默认
          </Button>
        </Space>
      </div>
      <Card className="iam-card" variant="borderless">
        <div className="iam-context-bar">
          <Typography.Text strong>配置用户</Typography.Text>
          <Select
            showSearch
            value={activeUserId}
            onChange={setActiveUserId}
            optionFilterProp="label"
            options={users.map((user) => ({
              value: user.id,
              label: `${user.displayName}（${user.username}）`,
            }))}
            style={{ minWidth: 280 }}
          />
          {activeUser && (
            <Space>
              <Tag color="blue">{activeUser.roleName || '未分配角色'}</Tag>
              <Typography.Text type="secondary">
                {activeUser.departmentName || '未设置部门'}
              </Typography.Text>
            </Space>
          )}
        </div>
        <div className="iam-inheritance-note">
          当前视图为个人覆盖；未配置覆盖时继承主角色权限。
        </div>
        <div className="iam-permission-grid">
          {Object.entries(grouped).map(([module, items]) => (
            <section className="iam-permission-section" key={module}>
              <div className="iam-section-heading">
                <Typography.Title level={5}>{moduleDisplayLabel(module)}</Typography.Title>
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
                    <Select
                      size="small"
                      disabled={!binding}
                      value={binding?.effect || 'ALLOW'}
                      options={[
                        { value: 'ALLOW', label: '允许' },
                        { value: 'DENY', label: '拒绝' },
                      ]}
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
