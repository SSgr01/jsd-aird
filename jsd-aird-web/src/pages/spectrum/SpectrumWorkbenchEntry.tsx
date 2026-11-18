import { useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';

/** The spectrum entry now creates a native expert session; legacy history is not imported. */
export function SpectrumWorkbenchEntry() {
  const [params] = useSearchParams();
  const ids = (params.get('chartIds') || '').split(',').filter((id) => /^[a-f0-9-]{36}$/i.test(id)).slice(0, 12);
  const target = `/agent/?spectrum=${encodeURIComponent(ids.join(','))}`;
  useEffect(() => { window.location.replace(target); }, [target]);
  return <p role="status">正在打开图谱分析助理…</p>;
}
