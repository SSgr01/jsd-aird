import { useEffect, useState } from 'react';

export function ContentLoading({ label = '正在加载内容' }: { label?: string }) {
  const [visible, setVisible] = useState(false);
  useEffect(() => { const timer = window.setTimeout(() => setVisible(true), 200); return () => clearTimeout(timer); }, []);
  return <div className="content-loading" aria-busy="true">{visible && <div role="status"><span>{label}</span><i /><i /><i /></div>}</div>;
}
