import { iamApi, type ProjectOwnerOption } from '@/services/iam/iam-api';
import { useEffect, useState } from 'react';

export function useProjectOwnerOptions(open: boolean) {
  const [options, setOptions] = useState<{ value: string; label: string }[]>([]);
  const [users, setUsers] = useState<ProjectOwnerOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!open) return;
    let active = true;
    setOptions([]);
    setUsers([]);
    setLoading(true);
    setError(false);
    void iamApi.projectOwnerOptions().then((users) => {
      if (!active) return;
      setUsers(users);
      const names = new Set<string>();
      setOptions(users.filter((user) => {
        if (!user.displayName || names.has(user.displayName)) return false;
        names.add(user.displayName);
        return true;
      }).map((user) => ({ value: user.displayName, label: `${user.displayName} (${user.username})` })));
    }).catch(() => {
      if (active) { setOptions([]); setUsers([]); setError(true); }
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [open]);

  return { options, users, loading, error };
}
