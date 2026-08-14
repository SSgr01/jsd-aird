import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { Button, Checkbox, Empty, Input, message, Popconfirm, Select, Space } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import dayjs from 'dayjs';
import { changePartnerStatus, getPartners } from '@/services/partners/partner-api';
import type { BusinessPartner } from '@/services/partners/partner-api';
import { PartnerPrototypeModal } from './PartnerPrototypeModals';
import './partner-pages.css';
import './partner-0730.css';
import './partner-prototype.css';

const customerLevels = ['重点客户', '普通客户', '潜在客户'];
const cooperationStatuses = ['潜在客户', '需求沟通', '合作中', '暂停', '已结束'];
const pageSize = 8;

export function PartnerListPage() {
  const [rows, setRows] = useState<BusinessPartner[]>([]);
  const [keyword, setKeyword] = useState('');
  const [industry, setIndustry] = useState<string>();
  const [level, setLevel] = useState<string>();
  const [cooperation, setCooperation] = useState<string>();
  const [owner, setOwner] = useState<string>();
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [modal, setModal] = useState<{
    mode: 'customer' | 'requirement' | 'followup';
    partner?: BusinessPartner;
  }>();
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [msg, holder] = message.useMessage();
  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(
        (await getPartners({ keyword: keyword || undefined, page: 1, size: 100 }))
          .items,
      );
    } finally {
      setLoading(false);
    }
  }, [keyword]);
  useEffect(() => {
    void load();
  }, [load]);

  const industries = useMemo(
    () => [...new Set(rows.map((x) => x.industry).filter(Boolean))] as string[],
    [rows],
  );
  const owners = useMemo(
    () => [...new Set(rows.flatMap((x) => x.ownerNames ?? []))].filter(Boolean),
    [rows],
  );
  const filtered = rows.filter(
    (x) =>
      (!industry || x.industry === industry) &&
      (!level || x.customerLevel === level) &&
      (!cooperation || x.cooperationStatus === cooperation) &&
      (!owner || (x.ownerNames ?? []).includes(owner)),
  );
  const totalPages = Math.max(1, Math.ceil(filtered.length / pageSize));
  const visible = filtered.slice((page - 1) * pageSize, page * pageSize);
  const visibleIds = visible.map((x) => x.id);
  const allVisibleSelected =
    visibleIds.length > 0 && visibleIds.every((id) => selectedIds.includes(id));
  const someVisibleSelected =
    visibleIds.some((id) => selectedIds.includes(id)) && !allVisibleSelected;
  const toggleAllVisible = (checked: boolean) => {
    setSelectedIds((prev) => {
      const rest = prev.filter((id) => !visibleIds.includes(id));
      return checked ? [...rest, ...visibleIds] : rest;
    });
  };
  const toggleOne = (id: string, checked: boolean) => {
    setSelectedIds((prev) =>
      checked
        ? prev.includes(id) ? prev : [...prev, id]
        : prev.filter((x) => x !== id),
    );
  };
  const reset = () => {
    setKeyword('');
    setIndustry(undefined);
    setLevel(undefined);
    setCooperation(undefined);
    setOwner(undefined);
    setPage(1);
  };
  const deactivate = async (targets: BusinessPartner[]) => {
    await Promise.all(
      targets
        .filter((x) => x.status === 'ACTIVE')
        .map((x) => changePartnerStatus(x.id, 'INACTIVE', x.version)),
    );
    msg.success('公司已停用，历史关联数据继续保留');
    setSelectedIds([]);
    await load();
  };
  const selectedPartners = rows.filter((x) => selectedIds.includes(x.id));
  const batchDeactivate = async () => {
    const targets = selectedPartners.filter((x) => x.status === 'ACTIVE');
    if (!targets.length) {
      msg.warning('没有可停用的客户');
      return;
    }
    await deactivate(targets);
  };

  return (
    <div className="cm-page">
      {holder}
      <div className="cm-page-head">
        <div>
          <h3>客户列表</h3>
          <p>统一管理公司档案、客户需求、关联项目及跟进记录。</p>
        </div>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => setModal({ mode: 'customer' })}
        >
          新建客户
        </Button>
      </div>
      <div className="cm-filter">
        <Input
          prefix={<SearchOutlined />}
          placeholder="搜索公司名称、简称"
          value={keyword}
          onChange={(e) => {
            setKeyword(e.target.value);
            setPage(1);
          }}
          allowClear
        />
        <Select
          placeholder="全部行业"
          value={industry}
          onChange={(v) => {
            setIndustry(v);
            setPage(1);
          }}
          allowClear
          options={industries.map((value) => ({ value }))}
        />
        <Select
          placeholder="全部等级"
          value={level}
          onChange={(v) => {
            setLevel(v);
            setPage(1);
          }}
          allowClear
          options={customerLevels.map((value) => ({ value }))}
        />
        <Select
          placeholder="合作状态"
          value={cooperation}
          onChange={(v) => {
            setCooperation(v);
            setPage(1);
          }}
          allowClear
          options={cooperationStatuses.map((value) => ({ value }))}
        />
        <Select
          placeholder="全部负责人"
          value={owner}
          onChange={(v) => {
            setOwner(v);
            setPage(1);
          }}
          allowClear
          options={owners.map((value) => ({ value }))}
        />
        <Button icon={<ReloadOutlined />} onClick={reset}>
          重置
        </Button>
      </div>
      <div className="cm-table-card" aria-busy={loading}>
        <div className="cm-table-wrap">
          <table className="cm-table">
            <thead>
              <tr>
                <th style={{ width: 40 }}>
                  <Checkbox
                    checked={allVisibleSelected}
                    indeterminate={someVisibleSelected}
                    onChange={(e) => toggleAllVisible(e.target.checked)}
                  />
                </th>
                <th>客户编号</th>
                <th>客户名称</th>
                <th>所属行业</th>
                <th>客户等级</th>
                <th>负责人</th>
                <th>合作状态</th>
                <th>客户需求</th>
                <th>关联项目</th>
                <th>最近跟进</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((x) => {
                const owners = (x.ownerNames && x.ownerNames.length > 0)
                  ? x.ownerNames
                  : [x.contacts[0]?.name].filter(Boolean) as string[];
                return (
                  <tr key={x.id}>
                    <td>
                      <Checkbox
                        checked={selectedIds.includes(x.id)}
                        onChange={(e) => toggleOne(x.id, e.target.checked)}
                        disabled={x.status === 'INACTIVE'}
                      />
                    </td>
                    <td>{x.partnerCode}</td>
                    <td>
                      <Link className="cm-customer-link" to={`/partners/${x.id}`}>
                        {x.name}
                      </Link>
                      <div className="cm-muted">{x.address || '—'}</div>
                    </td>
                    <td>{x.industry || '—'}</td>
                    <td>{x.customerLevel || '—'}</td>
                    <td>
                      {owners.length > 0 ? owners.join('、') : '—'}
                    </td>
                    <td>{x.cooperationStatus || '—'}</td>
                    <td>{x.requirementCount ?? 0} 条</td>
                    <td>{x.projectCount ?? 0} 个</td>
                    <td>{x.latestFollowUpAt ? dayjs(x.latestFollowUpAt).format('YYYY-MM-DD HH:mm') : '—'}</td>
                    <td>
                      <div className="cm-row-actions">
                        <Link to={`/partners/${x.id}`}>查看</Link>
                        {/* <button
                          className="cm-link-button"
                          onClick={() => setModal({ mode: 'customer', partner: x })}
                        >
                          编辑
                        </button> */}
                        <Popconfirm
                          title="删除该公司？"
                          description="历史关联数据不会被删除。"
                          disabled={x.status === 'INACTIVE'}
                          onConfirm={() => void deactivate([x])}
                        >
                          <Button danger type="link" size="small" disabled={x.status === 'INACTIVE'}>
                            删除
                          </Button>
                        </Popconfirm>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        {!loading && !visible.length && (
          <Empty className="cm-empty" description="没有符合筛选条件的公司" />
        )}
        <div className="cm-pagination">
          <span>
            共 {filtered.length} 条记录
            {selectedIds.length > 0 && ` · 已选 ${selectedIds.length} 项`}
          </span>
          <Space>
            {selectedIds.length > 0 && (
              <Popconfirm
                title={`批量停用 ${selectedIds.length} 个客户？`}
                description="历史关联数据不会被删除。"
                onConfirm={() => void batchDeactivate()}
              >
                <Button danger size="small">
                  批量删除
                </Button>
              </Popconfirm>
            )}
            <Button
              size="small"
              disabled={selectedIds.length === 0}
              onClick={() => setSelectedIds([])}
            >
              清除选择
            </Button>
            <Button size="small" disabled={page === 1} onClick={() => setPage(page - 1)}>
              上一页
            </Button>
            <span>
              {page} / {totalPages}
            </span>
            <Button size="small" disabled={page === totalPages} onClick={() => setPage(page + 1)}>
              下一页
            </Button>
          </Space>
        </div>
      </div>
      {modal && (
        <PartnerPrototypeModal
          mode={modal.mode}
          partner={modal.partner}
          open
          onClose={() => setModal(undefined)}
          onSaved={() => void load()}
        />
      )}
    </div>
  );
}
