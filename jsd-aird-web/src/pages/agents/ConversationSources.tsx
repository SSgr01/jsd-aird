import { Button, Input, List, Space, Tag, Typography } from 'antd';
import { useRef, useState } from 'react';
import { agentApi, type PrivateSource } from '@/services/agents/agent-api';

interface Props {
  sources: PrivateSource[];
  busy: boolean;
  active: boolean;
  onUpload: (file: File) => Promise<void>;
  onAcquire: (pmcid: string) => Promise<void>;
  onDetach: (id: string) => Promise<void>;
}

export function ConversationSources({
  sources,
  busy,
  active,
  onUpload,
  onAcquire,
  onDetach,
}: Props) {
  const input = useRef<HTMLInputElement>(null);
  const [pmcid, setPmcid] = useState('');
  return (
    <section className="research-private-sources" aria-label="会话私有资料">
      <Typography.Title level={4}>会话私有资料</Typography.Title>
      <Typography.Paragraph type="secondary">
        仅本账号在本会话中使用，不发布到知识库。解析就绪后可用于下一轮研究。
      </Typography.Paragraph>
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
          上传资料（最多 10 MiB）
        </Button>
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
      {active && (
        <Typography.Paragraph type="secondary">本轮结束或停止后可添加资料。</Typography.Paragraph>
      )}
      <List
        size="small"
        dataSource={sources}
        locale={{ emptyText: '尚未添加私有资料' }}
        renderItem={(source) => (
          <List.Item
            actions={[
              <Button key="download" href={agentApi.privateSourceUrl(source.id)}>
                原文件
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
                  <span>{source.name}</span>
                  <Tag>
                    {source.status === 'READY'
                      ? '可阅读'
                      : source.status === 'FAILED'
                        ? '解析失败'
                        : '解析中'}
                  </Tag>
                </Space>
              }
              description={
                <>
                  {source.bytes.toLocaleString()} 字节
                  {source.provenance?.doi ? ` · DOI ${source.provenance.doi}` : ''}
                  {source.provenance?.license ? ` · ${source.provenance.license}` : ''}
                </>
              }
            />
          </List.Item>
        )}
      />
    </section>
  );
}
