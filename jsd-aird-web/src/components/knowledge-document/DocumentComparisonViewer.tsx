import { FileTextOutlined, FormOutlined } from '@ant-design/icons';
import { Segmented, Typography } from 'antd';
import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';

export function DocumentComparisonViewer({ original, result, originalTitle = '原文件', resultTitle = '解析结果', resultExtra }: { original: ReactNode; result: ReactNode; originalTitle?: string; resultTitle?: string; resultExtra?: ReactNode }) {
  const [narrow, setNarrow] = useState(false); const [active, setActive] = useState<'source' | 'result'>('result');
  useEffect(() => { const query = window.matchMedia('(max-width: 900px)'); const apply = () => setNarrow(query.matches); apply(); query.addEventListener('change', apply); return () => query.removeEventListener('change', apply); }, []);
  if (narrow) return <div className="knowledge-comparison knowledge-comparison--narrow"><Segmented block value={active} onChange={(value) => setActive(value as 'source' | 'result')} options={[{ value: 'source', label: '原文件', icon: <FileTextOutlined /> }, { value: 'result', label: '解析结果', icon: <FormOutlined /> }]} /><ComparisonPane title={active === 'source' ? originalTitle : resultTitle} icon={active === 'source' ? <FileTextOutlined /> : <FormOutlined />} extra={active === 'result' ? resultExtra : undefined}>{active === 'source' ? original : result}</ComparisonPane></div>;
  return <div className="knowledge-comparison"><ComparisonPane title={originalTitle} icon={<FileTextOutlined />}>{original}</ComparisonPane><ComparisonPane title={resultTitle} icon={<FormOutlined />} extra={resultExtra}>{result}</ComparisonPane></div>;
}

function ComparisonPane({ title, icon, extra, children }: { title: string; icon: ReactNode; extra?: ReactNode; children: ReactNode }) { return <section className="knowledge-comparison-pane"><header><Typography.Text strong>{icon} {title}</Typography.Text>{extra}</header><div className="knowledge-comparison-body">{children}</div></section>; }
