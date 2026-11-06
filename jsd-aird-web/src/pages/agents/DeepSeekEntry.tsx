import { useEffect } from 'react';

export function DeepSeekEntry() {
  useEffect(() => { window.location.replace('/agent/'); }, []);
  return <p role="status">正在打开研发工作台…</p>;
}
