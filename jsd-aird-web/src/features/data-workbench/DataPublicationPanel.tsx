import { App, Alert, Button, Empty, Input, Modal, Popconfirm, Select, Space, Table, Tag, Timeline, Typography } from 'antd';
import { createPortal } from 'react-dom';
import { EyeOutlined } from '@ant-design/icons';
import { useCallback, useEffect, useRef, useState } from 'react';
import { usePermission } from '@/components/auth/usePermission';
import { dataApi, type DataPublication } from '@/services/data/data-api';
import { publicationLabels, actionLabels, snapshotRows } from './publication-display';

export function DataPublicationPanel({ jobId, refreshKey, canConfirm = false, disabled = false, readOnly = false, lazy = false, onChanged, onStateChange, onEdit, versionContainer }: {
  versionContainer?: HTMLElement | null;
  jobId: string; refreshKey?: string; canConfirm?: boolean; disabled?: boolean; readOnly?: boolean; lazy?: boolean;
  onChanged?: () => void; onStateChange?: (state: DataPublication) => void; onEdit?: () => void;
}) {
  const { message } = App.useApp();
  const [state, setState] = useState<DataPublication>();
  const [open, setOpen] = useState(false);
  const [reviewHistoryOpen, setReviewHistoryOpen] = useState(false);
  const [reviewVersionId, setReviewVersionId] = useState('ALL');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [submitOpen, setSubmitOpen] = useState(false);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [comment, setComment] = useState('');
  const [snapshot, setSnapshot] = useState<{ version: number; data: Record<string, unknown> }>();
  const [reviewers, setReviewers] = useState<Array<{ id: string; name: string }>>([]);
  const [reviewerId, setReviewerId] = useState<string>();
  const [reviewersLoading, setReviewersLoading] = useState(false);
  const [reviewersError, setReviewersError] = useState<string>();
  const requestSequence = useRef(0);
  const canUpdate = usePermission('data.update');
  const canSubmit = usePermission('data.submit');
  const canReview = usePermission('data.approve');

  const load = useCallback(async () => {
    const sequence = ++requestSequence.current;
    try {
      const next = await dataApi.publication(jobId);
      if (sequence === requestSequence.current) { setState(next); setError(undefined); onStateChange?.(next); }
      return next;
    } catch (err) { if (sequence === requestSequence.current) setError(err instanceof Error ? err.message : '审核信息加载失败'); return undefined; }
  }, [jobId, onStateChange]);
  const loadReviewers = useCallback(async () => {
    setReviewersLoading(true); setReviewersError(undefined);
    try {
      const items = await dataApi.dataReviewers(jobId);
      setReviewers(items);
      setReviewerId((current) => items.some((user) => user.id === current) ? current
        : items.some((user) => user.id === state?.reviewer?.id) ? state?.reviewer?.id
          : items.length === 1 ? items[0]?.id : undefined);
    } catch (err) { setReviewersError(err instanceof Error ? err.message : '发布审核人加载失败'); }
    finally { setReviewersLoading(false); }
  }, [jobId, state?.reviewer?.id]);
  useEffect(() => { if (!lazy || open) void load(); }, [load, refreshKey, lazy, open]);
  useEffect(() => { if (submitOpen) void loadReviewers(); }, [submitOpen, loadReviewers]);
  const run = async (action: () => Promise<unknown>, success: string) => {
    requestSequence.current++;
    setBusy(true);
    try { await action(); await load(); onChanged?.(); void message.success(success); return true; }
    catch (err) { void message.error(err instanceof Error ? err.message : '操作失败'); return false; }
    finally { setBusy(false); }
  };
  const showDetails = () => { setSnapshot(undefined); setOpen(true); };
  const showSnapshot = async (id: string, version: number) => {
    setBusy(true);
    try { setSnapshot({ version, data: await dataApi.publicationSnapshot(jobId, id) }); setOpen(true); }
    catch (err) { void message.error(err instanceof Error ? err.message : '快照读取失败'); }
    finally { setBusy(false); }
  };
  const activeSnapshot = state?.confirmedSnapshot;
  const reviewEvents = state?.events.filter((event) => ['SUBMITTED', 'APPROVED', 'RETURNED', 'WITHDRAWN'].includes(event.action)
    && (reviewVersionId === 'ALL' || event.snapshotId === reviewVersionId)) || [];
  const showReviewHistory = () => { setOpen(false); setReviewVersionId(state?.confirmedSnapshot?.id || state?.publishedSnapshotId || 'ALL'); setReviewHistoryOpen(true); };
  const revisionRequired = state?.legacyPublished && state.status === 'DRAFT' && !state.events.some((event) => event.action === 'REVISION_CREATED');
  const actions = state && <Space wrap>
    <Tag color={state.status === 'PUBLISHED' ? 'success' : state.status === 'RETURNED' ? 'error' : 'processing'}>{publicationLabels[state.status]}</Tag>
    {state.status === 'RETURNED' && <Typography.Text type="danger" style={{ maxWidth: 280 }} ellipsis={{ tooltip: true }}>驳回原因：{state.events.find((event) => event.action === 'RETURNED')?.comment || '请查看审核记录'}</Typography.Text>}
    {state.publishedSnapshotId && !['PUBLISHED', 'DISABLED'].includes(state.status) && <Tag color={state.publishedEnabled ? 'success' : 'default'}>{state.publishedEnabled ? '原发布版本继续有效' : '原发布版本已停用'}</Tag>}
    {!readOnly && canSubmit && ['DRAFT', 'RETURNED', 'CONFIRMED'].includes(state.status) && !revisionRequired && <Button
      type="primary" disabled={busy || disabled || (state.status !== 'CONFIRMED' && (!canUpdate || !canConfirm))}
      onClick={() => { setOpen(false); setSubmitOpen(true); }}>{state.status === 'RETURNED' ? '重新提交审核' : '提交审核'}</Button>}
    {!readOnly && canUpdate && !canSubmit && canConfirm && ['DRAFT', 'RETURNED'].includes(state.status) && <Button disabled={busy || disabled}
      onClick={() => void run(() => dataApi.publicationAction(jobId, 'confirm'), '数据已确认，可由有送审权限的人员提交发布审核')}>确认数据</Button>}
    {state.status === 'PENDING_REVIEW' && <>
      {activeSnapshot && <Button disabled={busy} onClick={() => void showSnapshot(activeSnapshot.id, activeSnapshot.versionNo)}>查看审核内容</Button>}
      {!readOnly && canReview && activeSnapshot && <><Button danger disabled={busy} onClick={() => { setOpen(false); setComment(''); setRejectOpen(true); }}>驳回</Button>
        <Button type="primary" disabled={busy} onClick={() => void run(() => dataApi.review(jobId, activeSnapshot.id, true), '审核已通过，可单独发布')}>审核通过</Button></>}
      {!readOnly && canSubmit && <Button disabled={busy} onClick={() => void run(() => dataApi.publicationAction(jobId, 'withdraw'), '已撤回，可继续修改')}>撤回送审</Button>}
    </>}
    {state.status === 'APPROVED' && <>
      {!readOnly && canReview && <Button type="primary" disabled={busy} onClick={() => void run(() => dataApi.publicationAction(jobId, 'publish'), '导入数据已发布并正式入库')}>发布</Button>}
      {!readOnly && canSubmit && <Button disabled={busy} onClick={() => void run(() => dataApi.publicationAction(jobId, 'withdraw'), '已撤回，修改后需重新提交审核')}>撤回修改</Button>}
    </>}
    {!readOnly && canUpdate && (state.status === 'PUBLISHED' || revisionRequired) && <Popconfirm title="停用这份已发布资料？" description="保留历史与引用，停用后不再参与 AI 检索。" onConfirm={() => { void run(() => dataApi.publicationAction(jobId, 'disable'), '已停用'); }}><Button disabled={busy}>停用</Button></Popconfirm>}
    {!readOnly && canUpdate && state.status === 'DISABLED' && <Button disabled={busy} onClick={() => void run(() => dataApi.publicationAction(jobId, 'restore'), '已恢复使用')}>恢复使用</Button>}
    {!readOnly && canUpdate && (['PUBLISHED', 'DISABLED'].includes(state.status) || revisionRequired) && <Button disabled={busy}
      onClick={() => void run(() => dataApi.publicationAction(jobId, 'revise'), '修订草稿已打开，原发布版本保持不变').then((ok) => { if (ok) { setOpen(false); onEdit?.(); } })}>创建修订</Button>}
    {!readOnly && lazy && onEdit && !revisionRequired && ['DRAFT', 'RETURNED', 'CONFIRMED'].includes(state.status) && <Button onClick={onEdit}>打开草稿</Button>}
  </Space>;

  const versionContent = state && (
          <main className="version-history-page">
            <div className="version-history-heading">
              <Typography.Title level={4}>版本记录</Typography.Title>
              <Typography.Text type="secondary">发布后版本锁定。修订不会改写历史快照，当前发布版继续保留。</Typography.Text>
            </div>
            <div className="version-history-list">
            {!state.versions.length && <Empty description="尚未提交版本，保存草稿后可提交审核" />}
            {state.versions.map((version) => {
              const currentPublished = version.id === state.publishedSnapshotId;
              const current = version.id === activeSnapshot?.id;
              const events = state.events.filter((event) => event.snapshotId === version.id);
              const published = events.some((event) => event.action === 'PUBLISHED');
              const label = currentPublished ? (state.publishedEnabled ? '已发布' : '已停用')
                : published ? '历史发布版' : current ? publicationLabels[state.status] : '历史草稿';
              const creator = events.find((event) => event.action === 'CONFIRMED')?.actorName;
              return <article className="version-history-item" key={version.id}>
                <div className="version-marker">V{version.versionNo}</div>
                <div>
                  <Space wrap><Typography.Text strong>{current ? '当前版本' : `版本 V${version.versionNo}`}</Typography.Text>
                    <Tag color={currentPublished && state.publishedEnabled ? 'green' : published ? 'default' : 'gold'}>{label}</Tag></Space>
                  <Typography.Paragraph type="secondary">版本时间：{new Date(version.confirmedAt).toLocaleString('zh-CN')}</Typography.Paragraph>
                  <Space wrap><Button size="small" icon={<EyeOutlined />} disabled={busy} onClick={() => void showSnapshot(version.id, version.versionNo)}>查看快照</Button>
                    {currentPublished && <Tag color="blue">当前发布版</Tag>}
                    {creator && <Typography.Text type="secondary">创建人：{creator}</Typography.Text>}</Space>
                </div>
              </article>;
            })}
            </div>
          </main>
  );

  return <>
    {versionContainer && createPortal(versionContent, versionContainer)}
    {lazy ? <Button onClick={() => showDetails()}>审核与版本记录</Button> : <Space wrap>
      {actions}
      {error && <Typography.Text type="danger">{error}</Typography.Text>}
      <Button onClick={showReviewHistory}>审核记录</Button>
    </Space>}
    <Modal title="数据审核记录" open={reviewHistoryOpen} footer={null} width={640} onCancel={() => setReviewHistoryOpen(false)} destroyOnHidden>
      <Select aria-label="审核版本" style={{ width: '100%', marginBottom: 24 }} value={reviewVersionId} onChange={setReviewVersionId}
        options={[{ value: 'ALL', label: '全部版本' }, ...(state?.versions || []).map((version) => ({ value: version.id, label: `V${version.versionNo}${version.id === state?.publishedSnapshotId ? ' · 当前发布' : ''}` }))]} />
      {reviewEvents.length ? <Timeline items={reviewEvents.map((event) => ({
        key: event.id,
        color: event.action === 'RETURNED' ? 'red' : event.action === 'APPROVED' ? 'green' : 'blue',
        children: <Space direction="vertical" size={2}>
          <Typography.Text strong>{event.action === 'RETURNED' ? '审核驳回' : actionLabels[event.action]}</Typography.Text>
          <Typography.Text type="secondary">{event.actorName || '未知用户'} · {new Date(event.createdAt).toLocaleString('zh-CN')}</Typography.Text>
          {event.snapshotId && <Typography.Text type="secondary">导入版本 V{state?.versions.find((version) => version.id === event.snapshotId)?.versionNo || '—'}</Typography.Text>}
          {event.comment && <Typography.Paragraph style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{event.comment}</Typography.Paragraph>}
        </Space>,
      }))} /> : <Typography.Text type="secondary">当前资料暂无审核操作记录。</Typography.Text>}
    </Modal>
    <Modal open={submitOpen} title="提交审核" okText="提交审核" confirmLoading={busy} okButtonProps={{ disabled: !reviewerId || reviewersLoading || Boolean(reviewersError) }}
      onCancel={() => setSubmitOpen(false)} onOk={() => void run(() => dataApi.publicationAction(jobId, 'submit', reviewerId), '已提交审核').then((ok) => { if (ok) setSubmitOpen(false); })}>
      <Space direction="vertical" style={{ width: '100%' }} size={16}>
        <Typography.Text>审核对象是当前已保存的导入数据。审核通过后可发布，发布后正式入库。</Typography.Text>
        <Select aria-label="发布审核人" showSearch optionFilterProp="label" placeholder="选择发布审核人" style={{ width: '100%' }} loading={reviewersLoading}
          value={reviewerId} onChange={setReviewerId} options={reviewers.map((user) => ({ value: user.id, label: user.name }))}
          notFoundContent="没有可用发布审核人，请联系管理员配置数据审核权限" />
        {reviewersError && <Alert type="error" message={reviewersError} action={<Button onClick={() => void loadReviewers()}>重试</Button>} />}
      </Space>
    </Modal>
    <Modal open={rejectOpen} title="驳回" okText="驳回" confirmLoading={busy} okButtonProps={{ disabled: !comment.trim() }} onCancel={() => setRejectOpen(false)}
      onOk={() => { if (activeSnapshot) void run(() => dataApi.review(jobId, activeSnapshot.id, false, comment), '已驳回，可在原工作台修改后重新提交').then((ok) => { if (ok) setRejectOpen(false); }); }}>
      <Input.TextArea aria-label="驳回原因" value={comment} onChange={(event) => setComment(event.target.value)} placeholder="请填写驳回原因（必填）" rows={3} />
    </Modal>
    <Modal open={open} title={snapshot ? `版本快照 V${snapshot.version}` : '版本记录'} width={1040} footer={null} onCancel={() => setOpen(false)} destroyOnHidden>
      <Space direction="vertical" style={{ width: '100%' }} size={16}>
        {error && <Alert type="error" showIcon message={error} action={<Button onClick={() => void load()}>重试</Button>} />}
        {snapshot ? <>
          <Button onClick={() => setSnapshot(undefined)}>返回版本记录</Button>
          <Typography.Text type="secondary">这是提交时保存的内容，后续修订不会改写此版本。</Typography.Text>
          <Table size="small" rowKey="key" dataSource={snapshotRows(snapshot.data)} scroll={{ x: 800 }} columns={[
            { title: '字段', dataIndex: 'field' }, { title: '原值', dataIndex: 'raw' }, { title: '有效值', dataIndex: 'effective' }, { title: '来源位置', dataIndex: 'location' },
          ]} />
        </> : state && <>
          {lazy && <Space wrap>{actions}<Button onClick={showReviewHistory}>审核记录</Button></Space>}
          {state.reviewer && <Typography.Text>发布审核人：{state.reviewer.name}</Typography.Text>}
          {state.legacyPublished && !state.publishedSnapshotId && <Tag>历史入库资料（无审核记录）</Tag>}
          {versionContent}
        </>}
      </Space>
    </Modal>
  </>;
}
