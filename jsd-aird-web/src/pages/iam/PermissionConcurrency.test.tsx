import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { RolePermissionsPage } from './RolePermissionsPage';
import { UserPermissionsPage } from './UserPermissionsPage';
import { HttpError } from '@/services/http/errors';

const mocks = vi.hoisted(() => ({
  roles: vi.fn(),
  users: vi.fn(),
  definitions: vi.fn(),
  rolePermissions: vi.fn(),
  userPermissions: vi.fn(),
  saveRolePermissions: vi.fn(),
  saveUserPermissions: vi.fn(),
}));
vi.mock('@/services/iam/iam-api', () => ({ iamApi: mocks }));
vi.mock('./PermissionTargetPicker', () => ({ PermissionTargetPicker: () => null }));
interface PermissionData {
  version: number;
  bindings: Array<{
    permissionCode: string;
    effect: 'ALLOW';
    scopeType: string;
    targetIds: string[];
  }>;
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
const allow = {
  permissionCode: 'project.view',
  effect: 'ALLOW' as const,
  scopeType: 'ALL',
  targetIds: [],
};
beforeEach(() => {
  vi.resetAllMocks();
  mocks.roles.mockResolvedValue(
    ['a', 'b'].map((id) => ({
      id,
      code: id,
      name: `Role ${id.toUpperCase()}`,
      builtin: false,
      enabled: true,
      policyVersion: 0,
    })),
  );
  mocks.users.mockResolvedValue({
    items: ['a', 'b'].map((id) => ({
      id,
      username: id,
      displayName: `User ${id.toUpperCase()}`,
      status: 'ACTIVE',
      authVersion: 0,
    })),
  });
  mocks.definitions.mockResolvedValue([
    { code: 'project.view', name: '查看项目', module: 'project', risk: 'LOW', defaultScope: 'ALL' },
  ]);
  mocks.saveRolePermissions.mockResolvedValue({ version: 13 });
  mocks.saveUserPermissions.mockResolvedValue({ version: 13 });
});

describe.each(['role', 'user'] as const)('%s permission request ownership', (kind) => {
  const saveName = kind === 'role' ? /保存权限/ : /保存覆盖/;
  const read = () => (kind === 'role' ? mocks.rolePermissions : mocks.userPermissions);
  const write = () => (kind === 'role' ? mocks.saveRolePermissions : mocks.saveUserPermissions);
  const mount = () =>
    render(<App>{kind === 'role' ? <RolePermissionsPage /> : <UserPermissionsPage />}</App>);
  const chooseB = async () => {
    if (kind === 'role') fireEvent.click(await screen.findByRole('tab', { name: 'Role B' }));
    else {
      await screen.findByText('User A（a）');
      fireEvent.mouseDown(screen.getAllByRole('combobox')[0]!);
      fireEvent.click(await screen.findByText('User B（b）'));
    }
  };
  it('ignores a late previous-owner response and blocks saving until the new version arrives', async () => {
    const a = deferred<PermissionData>();
    const b = deferred<PermissionData>();
    read().mockImplementation((id: string) => (id === 'a' ? a.promise : b.promise));
    mount();
    await waitFor(() => expect(read()).toHaveBeenCalledWith('a', expect.any(AbortSignal)));
    expect(screen.getByRole('button', { name: saveName })).toBeDisabled();
    await chooseB();
    await act(async () => {
      a.resolve({ version: 7, bindings: [allow] });
      await Promise.resolve();
    });
    expect(screen.getByRole('button', { name: saveName })).toBeDisabled();
    await act(async () => {
      b.resolve({ version: 12, bindings: [] });
      await Promise.resolve();
    });
    await waitFor(() => expect(screen.getByRole('button', { name: saveName })).toBeEnabled());
    expect(screen.getByRole('checkbox', { name: /查看项目/ })).not.toBeChecked();
    fireEvent.click(screen.getByRole('button', { name: saveName }));
    await waitFor(() => expect(write()).toHaveBeenCalledExactlyOnceWith('b', 12, []));
  });
  it('keeps the new owners version when a previous-owner save completes late', async () => {
    read().mockImplementation((id: string) =>
      Promise.resolve({ version: id === 'a' ? 1 : 12, bindings: [] }),
    );
    const pending = deferred<{ version: number }>();
    write().mockReturnValueOnce(pending.promise).mockResolvedValue({ version: 13 });
    mount();
    await waitFor(() => expect(screen.getByRole('button', { name: saveName })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: saveName }));
    await waitFor(() => expect(write()).toHaveBeenCalledOnce());
    await chooseB();
    await waitFor(() => expect(read()).toHaveBeenCalledWith('b', expect.any(AbortSignal)));
    await act(async () => {
      pending.resolve({ version: 2 });
      await Promise.resolve();
    });
    await waitFor(() => expect(screen.getByRole('button', { name: saveName })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: saveName }));
    await waitFor(() => expect(write()).toHaveBeenLastCalledWith('b', 12, []));
  });
  it('retains unsaved permission choices after 409 and never refetches a version to repeat the save', async () => {
    read().mockResolvedValue({ version: 4, bindings: [] });
    write().mockRejectedValue(
      new HttpError('权限已被其他管理员修改', 'POLICY_VERSION_CONFLICT', 409),
    );
    mount();
    await waitFor(() => expect(screen.getByRole('button', { name: saveName })).toBeEnabled());
    fireEvent.click(screen.getByRole('checkbox', { name: /查看项目/ }));
    fireEvent.click(screen.getByRole('button', { name: saveName }));
    await waitFor(() => expect(write()).toHaveBeenCalledOnce());
    expect(screen.getByRole('checkbox', { name: /查看项目/ })).toBeChecked();
    expect(read()).toHaveBeenCalledOnce();
  });
});
