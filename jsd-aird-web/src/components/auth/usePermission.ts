import { useAuthStore } from '@/stores/auth-store';
import { hasPermission } from '@/routes/route-permissions';

export function usePermission(permission: string) {
  return useAuthStore((state) => hasPermission(permission, state.user?.permissions ?? []));
}
