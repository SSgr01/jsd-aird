import { useAuthStore } from '@/stores/auth-store';

export function usePermission(permission: string) {
  return useAuthStore((state) => state.user?.permissions.includes(permission) ?? false);
}
