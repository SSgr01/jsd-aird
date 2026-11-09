import { Button, Input, List, Space, Typography } from 'antd';
import { useRef, useState } from 'react';
import { agentApi, type PrivateSource } from '@/services/agents/agent-api';

interface Props {
  sources: PrivateSource[];
  busy: boolean;
  active: boolean;
  onUpload: (file: File) => Promise<void>;
  onAcquire: (pmcid: string) => Promise<void>;
  onDetach: (id: string) => Promise<void>;
  onRead?: (source: PrivateSource) => void;
  loading?: boolean;
  failed?: boolean;
}

export function ConversationSources({
  sources,
  busy,
  active,
  onUpload,
  onAcquire,
  onDetach,
  onRead,
  loading,
  failed,
}: Props) {
  const input = useRef<HTMLInputElement>(null);
  const [pmcid, setPmcid] = useState('');
  return (
    <section className="research-private-sources" aria-label="上传的文件">
      <Space wrap>
        <input
          ref={input}
          type="file"
          hidden
          accept=".pdf,.docx,.xlsx,.csv,.md,.txt"
          aria-label="选择私有资料"
          onChange={(event) => {
            const file = event.target.files?.[0];
            event.target.value = '';
            if (file) void onUpload(file);
          }}
        />
        <Button disabled={busy || active} onClick={() => input.current?.click()}>
          上传文件
        </Button>
        <details className="research-open-access">
          <summary>获取公开文献</summary>
          <Space wrap>
            <Input
              aria-label="开放获取文献 PMCID"
              placeholder="PMC10530767"
              value={pmcid}
              disabled={busy || active}
              maxLength={13}
              onChange={(event) => setPmcid(event.target.value.trim().toUpperCase())}
            />
            <Button
              disabled={busy || active || !/^PMC\d{1,10}$/.test(pmcid)}
              onClick={() => void onAcquire(pmcid)}
            >
              获取开放全文
            </Button>
          </Space>
        </details>
      </Space>
      {active && (
        <Typography.Paragraph type="secondary">本轮结束或停止后可添加资料。</Typography.Paragraph>
      )}
      {(sources.length > 0 || loading) && (
        <List
          size="small"
          loading={loading}
          dataSource={sources}
          locale={{ emptyText: loading || failed ? null : '还没有上传文件' }}
          renderItem={(source) => (
            <List.Item
              actions={[
                ...(onRead && source.status === 'READY'
                  ? [
                      <Button key="preview" type="link" onClick={() => onRead(source)}>
                        预览
                      </Button>,
                    ]
                  : []),
                <Button key="download" href={agentApi.privateSourceUrl(source.id)}>
                  下载
                </Button>,
                <Button
                  key="detach"
                  disabled={busy || active}
                  onClick={() => void onDetach(source.id)}
                >
                  移出本会话
                </Button>,
              ]}
            >
              <List.Item.Meta
                title={
                  <Space wrap>
                    {onRead && source.status === 'READY' ? (
                      <button className="research-file-name" onClick={() => onRead(source)}>
                        {source.name}
                      </button>
                    ) : (
                      <span>{source.name}</span>
                    )}
                    <Typography.Text type="secondary">
                      {source.status === 'READY'
                        ? '可阅读'
                        : source.status === 'FAILED'
                          ? '解析失败'
                          : '解析中'}
                    </Typography.Text>
                  </Space>
                }
                description={
                  <>
                    {source.bytes >= 1024 * 1024
                      ? `${(source.bytes / (1024 * 1024)).toFixed(1)} MB`
                      : `${Math.max(1, Math.ceil(source.bytes / 1024))} KB`}
                    {source.provenance?.doi ? ` · DOI ${source.provenance.doi}` : ''}
                    {source.provenance?.license ? ` · ${source.provenance.license}` : ''}
                  </>
                }
              />
            </List.Item>
          )}
        />
      )}
      {!sources.length && !loading && !failed && <p className="harness-muted">还没有上传文件</p>}
    </section>
  );
}
