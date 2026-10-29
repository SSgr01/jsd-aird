import { useAuthStore } from '@/stores/auth-store';
import { notifyAuthRequired } from '@/services/http/auth-events';
import { installQuerySync } from '@/services/query/query-sync';
import { invalidateDomains } from '@/services/query/query-client';

export function installSessionSync() {
  installQuerySync({
    scope: () => useAuthStore.getState().user,
    invalidate: (domains) => {
      void invalidateDomains(domains, false);
    },
    session: (action) => {
      if (action === 'ended') notifyAuthRequired();
      else void useAuthStore.getState().resync();
    },
  });
}
