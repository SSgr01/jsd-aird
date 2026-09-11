import { AppstoreOutlined, CopyOutlined, DownloadOutlined, PlusOutlined, ReloadOutlined, SearchOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { App, Breadcrumb, Button, Checkbox, Empty, Input, Pagination, Popconfirm, Select, Space, Tag, Tooltip } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import dayjs from 'dayjs';
import * as XLSX from 'xlsx';
import { changePartnerStatus, createPartner, getPartners } from '@/services/partners/partner-api';
import type { BusinessPartner } from '@/services/partners/partner-api';
import { errorMessage } from '@/services/http/errors';
import { PartnerPrototypeModal } from './PartnerPrototypeModals';
import './partner-pages.css';
import './partner-0730.css';
import './partner-prototype.css';
import '@/styles/management-list.css';

const customerLevels = ['未分类', '重点客户', '普通客户', '潜在客户'];
const cooperationStatuses = ['潜在客户', '需求沟通', '合作中', '暂停', '已结束'];
const levelColorMap: Record<string, string> = {
  重点客户: 'red',
  普通客户: 'blue',
  潜在客户: 'default',
};
const statusTagClassMap: Record<string, string> = {
  合作中: 'status-in_progress',
  需求沟通: 'status-pending',
  暂停: 'status-paused',
  已结束: 'status-completed',
  潜在客户: 'status-not_started',
};
const statusTagClass = (value?: string) => `pm-tag ${statusTagClassMap[value ?? ''] ?? 'status-not_started'}`;
const DEFAULT_PAGE_SIZE = 10;

type PartnerFilterOptions = {
  industries: string[];
  customerLevels: string[];
  cooperationStatuses: string[];
  owners: string[];
};

const uniqueText = (values: Array<string | undefined>) => [...new Set(
  values.map((value) => value?.trim()).filter((value): value is string => Boolean(value)),
)];

export function PartnerListPage() {
  const [rows, setRows] = useState<BusinessPartner[]>([]);
  const [keyword, setKeyword] = useState('');
  const [industry, setIndustry] = useState<string>();
  const [level, setLevel] = useState<string>();
  const [cooperation, setCooperation] = useState<string>();
  const [owner, setOwner] = useState<string>();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [copying, setCopying] = useState(false);
  const [exporting, setExporting] = useState(false);
  const copyingRef = useRef(false);
  const [filterOptions, setFilterOptions] = useState<PartnerFilterOptions>({
    industries: [],
    customerLevels,
    cooperationStatuses,
    owners: [],
  });
  const [modal, setModal] = useState<{
    mode: 'customer' | 'requirement' | 'followup';
    partner?: BusinessPartner;
  }>();
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [selectedPartnersById, setSelectedPartnersById] = useState<Record<string, BusinessPartner>>({});
  const [viewMode, setViewMode] = useState<'table' | 'card'>('table');
  const { message: msg } = App.useApp();
  const query = useMemo(() => ({
    keyword: keyword || undefined,
    industry: industry || undefined,
    customerLevel: level || undefined,
    cooperationStatus: cooperation || undefined,
    owner: owner || undefined,
    status: 'ACTIVE',
  }), [keyword, industry, level, cooperation, owner]);
  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await getPartners({ ...query, page, size: pageSize });
      setRows(result.items);
      setTotal(result.total);
      setSelectedPartnersById((prev) => {
        const next = { ...prev };
        result.items.forEach((item) => { next[item.id] = item; });
        return next;
      });
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, query]);
  useEffect(() => {
    void load();
  }, [load]);

  const currentPageIndustries = useMemo(() => uniqueText(rows.map((x) => x.industry)), [rows]);
  const currentPageOwners = useMemo(
    () => uniqueText(rows.flatMap((x) => x.ownerNames ?? [])),
    [rows],
  );
  const loadFilterOptions = useCallback(async () => {
    try {
      const result = await getPartners({ status: 'ACTIVE', page: 1, size: 100 });
      setFilterOptions({
        industries: uniqueText(result.items.map((x) => x.industry)),
        customerLevels: uniqueText([
          ...customerLevels,
          ...result.items.map((x) => x.customerLevel),
        ]),
        cooperationStatuses: uniqueText([
          ...cooperationStatuses,
          ...result.items.map((x) => x.cooperationStatus),
        ]),
        owners: uniqueText(result.items.flatMap((x) => x.ownerNames ?? [])),
      });
    } catch {
      // Keep the built-in options available if the independent options request fails.
    }
  }, []);
  useEffect(() => {
    void loadFilterOptions();
  }, [loadFilterOptions]);
  const visible = rows;
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
    setSelectedPartnersById((prev) => {
      const next = { ...prev };
      if (checked) visible.forEach((item) => { next[item.id] = item; });
      else visibleIds.forEach((id) => { delete next[id]; });
      return next;
    });
  };
  const toggleOne = (id: string, checked: boolean) => {
    setSelectedIds((prev) => checked
      ? prev.includes(id) ? prev : [...prev, id]
      : prev.filter((x) => x !== id));
    const partner = visible.find((item) => item.id === id);
    setSelectedPartnersById((prev) => {
      const next = { ...prev };
      if (checked && partner) next[id] = partner;
      else delete next[id];
      return next;
    });
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
    try {
      await Promise.all(
        targets
          .filter((x) => x.status === 'ACTIVE')
          .map((x) => changePartnerStatus(x.id, 'INACTIVE', x.version)),
      );
      msg.success('删除成功');
      setSelectedIds([]);
      setSelectedPartnersById({});
      await load();
    } catch (error) {
      msg.error(errorMessage(error, '删除失败'));
    }
  };
  const selectedPartners = selectedIds
    .map((id) => selectedPartnersById[id])
    .filter((partner): partner is BusinessPartner => Boolean(partner));
  const copySelected = async () => {
    if (copyingRef.current) return;
    if (!selectedPartners.length) {
      msg.warning('请先选择要复制的客户');
      return;
    }
    copyingRef.current = true;
    setCopying(true);
    try {
      const stamp = Date.now().toString(36).toUpperCase();
      await Promise.all(selectedPartners.map((partner, index) => {
        const suffix = '（副本）';
        return createPartner({
          partnerCode: `${partner.partnerCode}-COPY-${stamp}-${index}`.slice(0, 32),
          name: `${partner.name.slice(0, 16)}${suffix}`,
          industry: partner.industry,
          address: partner.address,
          remark: partner.remark,
          customerLevel: partner.customerLevel,
          cooperationStatus: partner.cooperationStatus,
          mainBusiness: partner.mainBusiness,
          customFields: partner.customFields,
        });
      }));
      msg.success(`已复制 ${selectedPartners.length} 个客户，复制结果未继承原客户的需求、项目和跟进关系`);
      setSelectedIds([]);
      setSelectedPartnersById({});
      await load();
    } catch (error) {
      msg.error(errorMessage(error, '复制失败'));
    } finally {
      copyingRef.current = false;
      setCopying(false);
    }
  };
  const batchDeactivate = async () => {
    const targets = selectedPartners.filter((x) => x.status === 'ACTIVE');
    if (!targets.length) {
      msg.warning('没有可删除的客户');
      return;
    }
    await deactivate(targets);
  };
  const exportRows = async () => {
    setExporting(true);
    try {
      let exportPartners = selectedPartners;
      if (!exportPartners.length) {
        const pageSizeForExport = 200;
        const firstPage = await getPartners({ ...query, page: 1, size: pageSizeForExport });
        const pageCount = Math.ceil(firstPage.total / pageSizeForExport);
        const remainingPages = await Promise.all(
          Array.from({ length: Math.max(0, pageCount - 1) }, (_, index) => getPartners({
            ...query,
            page: index + 2,
            size: pageSizeForExport,
          })),
        );
        exportPartners = [firstPage, ...remainingPages]
          .flatMap((result) => result.items)
          .slice(0, firstPage.total);
      }
      const data = exportPartners.map((x) => ({
        客户编号: x.partnerCode,
        客户名称: x.name,
        所属行业: x.industry || '',
        客户等级: x.customerLevel || '',
        负责人: (x.ownerNames ?? []).join('、'),
        合作状态: x.cooperationStatus || '',
        客户需求: x.requirementCount ?? 0,
        关联项目: x.projectCount ?? 0,
        最近跟进: x.latestFollowUpAt
          ? dayjs(x.latestFollowUpAt).format('YYYY-MM-DD HH:mm')
          : '',
      }));
      const ws = XLSX.utils.json_to_sheet(data);
      const wb = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(wb, ws, '客户列表');
      XLSX.writeFile(wb, `客户列表_${selectedPartners.length ? '选中_' : ''}${dayjs().format('YYYYMMDD_HHmmss')}.xlsx`);
      msg.success(`已导出 ${exportPartners.length} 个客户`);
    } catch (error) {
      msg.error(errorMessage(error, '客户列表导出失败'));
    } finally {
      setExporting(false);
    }
  };

  return (
    <div className="cm-page pm-unified-list-page cm-customer-list-page">
      <div className="cm-page-head cm-customer-page-intro">
        <div>
          <Breadcrumb items={[{ title: '客户管理' }, { title: '客户列表' }]} />
          <h3>客户列表</h3>
          <p>统一管理公司档案、客户需求、关联项目及跟进记录。</p>
        </div>
      </div>

      <div className="cm-filter cm-filter-row">
        <Input
          prefix={<SearchOutlined />}
          placeholder="搜索公司名称、简称、负责人"
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
          options={(filterOptions.industries.length ? filterOptions.industries : currentPageIndustries)
            .map((value) => ({ value }))}
        />
        <Select
          placeholder="全部等级"
          value={level}
          onChange={(v) => {
            setLevel(v);
            setPage(1);
          }}
          allowClear
          options={filterOptions.customerLevels.map((value) => ({ value }))}
        />
        <Select
          placeholder="合作状态"
          value={cooperation}
          onChange={(v) => {
            setCooperation(v);
            setPage(1);
          }}
          allowClear
          options={filterOptions.cooperationStatuses.map((value) => ({ value }))}
        />
        <Select
          placeholder="全部负责人"
          value={owner}
          onChange={(v) => {
            setOwner(v);
            setPage(1);
          }}
          allowClear
          options={(filterOptions.owners.length ? filterOptions.owners : currentPageOwners)
            .map((value) => ({ value }))}
        />
        <Button className="cm-filter-reset" icon={<ReloadOutlined />} onClick={reset}>
          重置
        </Button>
      </div>

      <div className="cm-batch-row">
        <div className="cm-batch-summary">
          <span className="cm-muted">已选 {selectedIds.length} 项</span>
          {selectedIds.length > 0 && (
            <Button type="link" size="small" onClick={() => setSelectedIds([])}>
              清除已选
            </Button>
          )}
        </div>
        <div className="cm-batch-actions">
          <Button icon={<DownloadOutlined />} onClick={() => void exportRows()} loading={exporting}>
            导出
          </Button>
          <Button icon={<CopyOutlined />} onClick={() => void copySelected()} disabled={!selectedIds.length} loading={copying}>
            复制
          </Button>
          <Popconfirm
            title={`批量删除 ${selectedIds.length} 个客户？`}
            description="历史关联数据不会被删除。"
            disabled={selectedIds.length === 0}
            onConfirm={() => void batchDeactivate()}
          >
            <Button danger disabled={selectedIds.length === 0}>
              删除
            </Button>
          </Popconfirm>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setModal({ mode: 'customer' })}
          >
            新建客户
          </Button>
          <Space.Compact>
            <Tooltip title="卡片视图">
              <Button
                className={viewMode === 'card' ? 'cm-view-btn active' : 'cm-view-btn'}
                icon={<AppstoreOutlined />}
                onClick={() => setViewMode('card')}
              />
            </Tooltip>
            <Tooltip title="表格视图">
              <Button
                className={viewMode === 'table' ? 'cm-view-btn active' : 'cm-view-btn'}
                icon={<UnorderedListOutlined />}
                onClick={() => setViewMode('table')}
              />
            </Tooltip>
            </Space.Compact>
          </div>
        </div>
      <div className="cm-table-card" aria-busy={loading}>
        {viewMode === 'table' ? (
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
                      <td>{x.customerLevel ? <Tag color={levelColorMap[x.customerLevel] ?? 'default'}>{x.customerLevel}</Tag> : '—'}</td>
                      <td>
                        {owners.length > 0 ? owners.join('、') : '—'}
                      </td>
                      <td>{x.cooperationStatus ? <span className={statusTagClass(x.cooperationStatus)}>{x.cooperationStatus}</span> : '—'}</td>
                      <td>{x.requirementCount ?? 0} 条</td>
                      <td>{x.projectCount ?? 0} 个</td>
                      <td>{x.latestFollowUpAt ? dayjs(x.latestFollowUpAt).format('YYYY-MM-DD HH:mm') : '—'}</td>
                      <td>
                        <div className="cm-row-actions management-table-actions">
                          <Link to={`/partners/${x.id}`}>查看</Link>
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
        ) : (
          <div className="cm-customer-cards">
            {visible.length > 0 && (
              <div className="cm-customer-cards-head">
                <Checkbox
                  checked={allVisibleSelected}
                  indeterminate={someVisibleSelected}
                  onChange={(e) => toggleAllVisible(e.target.checked)}
                />
                <span className="cm-muted">全选当前页</span>
              </div>
            )}
            <div className="cm-customer-cards-grid">
              {visible.map((x) => {
                const owners = (x.ownerNames && x.ownerNames.length > 0)
                  ? x.ownerNames
                  : [x.contacts[0]?.name].filter(Boolean) as string[];
                const levelColor = levelColorMap[x.customerLevel ?? ''] ?? 'default';
                return (
                  <div className={`cm-customer-card ${selectedIds.includes(x.id) ? 'is-selected' : ''}`} key={x.id}>
                    <div className="cm-customer-card-check">
                      <Checkbox
                        checked={selectedIds.includes(x.id)}
                        onChange={(e) => toggleOne(x.id, e.target.checked)}
                        disabled={x.status === 'INACTIVE'}
                      />
                    </div>
                    <div className="cm-customer-card-head">
                      <div className="cm-customer-card-titleline">
                        <Link className="cm-customer-card-name" to={`/partners/${x.id}`} title={x.name}>{x.name}</Link>
                        <div className="cm-customer-card-tags">
                          {x.customerLevel && (
                            <Tag color={levelColor}>{x.customerLevel}</Tag>
                          )}
                          {x.cooperationStatus && (
                            <span className={statusTagClass(x.cooperationStatus)}>{x.cooperationStatus}</span>
                          )}
                        </div>
                      </div>
                      <div className="cm-customer-card-subline">
                        <span>{x.partnerCode}</span>
                        {x.industry && <><span className="cm-customer-card-dot">·</span><span>{x.industry}</span></>}
                      </div>
                    </div>
                    <div className="cm-customer-card-owner">
                      <span className="cm-muted">负责人</span>
                      <span className="cm-customer-card-owner-value">
                        {owners.length > 0 ? owners.join('、') : '—'}
                      </span>
                    </div>
                    <div className="cm-customer-card-stats">
                      <span className="cm-customer-card-stat">客户需求 {x.requirementCount ?? 0} 条</span>
                      <span className="cm-customer-card-stat">关联项目 {x.projectCount ?? 0} 个</span>
                    </div>
                    <div className="cm-customer-card-foot">
                      <span className="cm-muted">
                        最近跟进：{x.latestFollowUpAt ? dayjs(x.latestFollowUpAt).format('YYYY-MM-DD HH:mm') : '—'}
                      </span>
                      <Space size={8}>
                        <Link className="cm-link-button" to={`/partners/${x.id}`}>查看</Link>
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
                      </Space>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        )}
        {!loading && !visible.length && (
          <Empty className="cm-empty" description="没有符合筛选条件的公司" />
        )}
        <div className="cm-pagination">
          <div className="cm-pagination-right">
            <span>
              共 {total} 条记录
              {selectedIds.length > 0 && ` · 已选 ${selectedIds.length} 项`}
            </span>
            <Pagination
              current={page}
              pageSize={pageSize}
              total={total}
              showSizeChanger
              showQuickJumper={{ goButton: <Button size="small">跳转</Button> }}
              pageSizeOptions={[10, 20, 30, 50]}
              onChange={(p, s) => { setPage(p); setPageSize(s); }}
            />
          </div>
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
