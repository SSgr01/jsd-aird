import { DeleteOutlined, FolderOpenOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Checkbox, Empty, Input, message, Modal, Pagination, Select, Space } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  qualityApi,
  type QualityCategory,
  type QualityRecord,
  type QualityType,
  type QualityTypeId,
} from '@/services/quality/quality-api';
import './quality-pages.css';

type Draft = QualityRecord & { newRow?: boolean };
function textValue(value: unknown, fallback = '') {
  if (value === null || value === undefined) return fallback;
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return fallback;
  }
}

export function QualityDataPage() {
  const navigate = useNavigate();
  const [types, setTypes] = useState<QualityType[]>([]),
    [typeId, setTypeId] = useState<QualityTypeId>('standard'),
    [categories, setCategories] = useState<QualityCategory[]>([]),
    [categoryId, setCategoryId] = useState<string>();
  const [rows, setRows] = useState<QualityRecord[]>([]),
    [draft, setDraft] = useState<Draft[]>([]),
    [deleted, setDeleted] = useState<string[]>([]),
    [selected, setSelected] = useState<Set<string>>(new Set()),
    [editing, setEditing] = useState(false),
    [dirty, setDirty] = useState(false);
  const [keyword, setKeyword] = useState(''),
    [page, setPage] = useState(1),
    [size, setSize] = useState(10),
    [total, setTotal] = useState(0);
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [msg, holder] = message.useMessage();
  const type = types.find((t) => t.id === typeId);
  const activeRows = editing ? draft : rows;
  const loadCategories = useCallback(async () => {
    const cs = await qualityApi.categories(typeId);
    setCategories(cs);
    setCategoryId((v) => (cs.some((c) => c.id === v) ? v : cs[0]?.id));
  }, [typeId]);
  const loadRows = useCallback(async () => {
    if (!categoryId) return;
    try {
      const r = await qualityApi.records({
        type: typeId,
        categoryId,
        keyword: keyword || undefined,
        page,
        size,
      });
      setRows(r.items);
      setTotal(r.total);
      setSelected(new Set());
    } catch {
      msg.error('品管数据加载失败');
    }
  }, [typeId, categoryId, keyword, page, size, msg]);
  useEffect(() => {
    void qualityApi.types().then(setTypes);
  }, []);
  useEffect(() => {
    void loadCategories();
  }, [loadCategories]);
  useEffect(() => {
    void loadRows();
  }, [loadRows]);
  const guard = () => !dirty || window.confirm('当前表格有未保存修改，确定放弃吗？');
  const switchType = (id: QualityTypeId) => {
    if (!guard()) return;
    setEditing(false);
    setDirty(false);
    setTypeId(id);
    setCategoryId(undefined);
    setFilters({});
    setPage(1);
  };
  const idKey =
    typeId === 'standard'
      ? 'fileNo'
      : typeId === 'record'
        ? 'recordNo'
        : typeId === 'coa'
          ? 'reportNo'
          : typeId === 'defect'
            ? 'defectNo'
            : typeId === 'manual'
              ? 'manualNo'
              : typeId === 'msds'
                ? 'msdsNo'
                : 'labelNo';
  const filterFields = (type?.fields || [])
    .filter((field) => field.key !== idKey)
    .slice(0, 2);
  const visibleRows = activeRows.filter((row) =>
    filterFields.every((field) => {
      const value = filters[field.key];
      return !value || textValue(row.data[field.key]).toLowerCase().includes(value.toLowerCase());
    }),
  );
  const newNo = () => `${type?.prefix || 'QC'}-${Date.now().toString(36).toUpperCase()}`;
  const startEdit = () => {
    setDraft(rows.map((r) => ({ ...r, data: { ...r.data } })));
    setDeleted([]);
    setEditing(true);
    setDirty(false);
  };
  const addRow = () => {
    if (!type || !categoryId) return;
    const no = newNo(),
      data: Record<string, unknown> = { [idKey]: no };
    for (const f of type.fields)
      if (f.kind === 'date') data[f.key] = new Date().toISOString().slice(0, 10);
    setDraft((v) => [
      ...v,
      {
        id: `new-${crypto.randomUUID()}`,
        businessType: typeId,
        categoryId,
        categoryName: '',
        businessNo: no,
        data,
        lockVersion: 0,
        createdAt: '',
        updatedAt: '',
        newRow: true,
      },
    ]);
    setEditing(true);
    setDirty(true);
  };
  const update = (i: number, key: string, value: string) => {
    setDraft((v) =>
      v.map((r, x) =>
        x === i
          ? {
              ...r,
              data: { ...r.data, [key]: value },
              businessNo: key === idKey ? value : r.businessNo,
            }
          : r,
      ),
    );
    setDirty(true);
  };
  const save = async () => {
    if (!type || !categoryId) return;
    const activeDraft = draft.filter((r) => !deleted.includes(r.id));
    const businessNumbers = new Set<string>();
    for (const r of activeDraft) {
      const businessNo = textValue(r.businessNo || r.data[idKey]).trim();
      if (!businessNo) return msg.warning(`${type.name}编号不能为空`);
      if (businessNumbers.has(businessNo)) return msg.warning(`${type.name}编号不能重复`);
      businessNumbers.add(businessNo);
      for (const f of type.fields) {
        const value = textValue(r.data[f.key]).trim();
        if (f.required && !value) return msg.warning(`${f.label}不能为空`);
        if (f.kind === 'date' && value) {
          const date = new Date(`${value}T00:00:00`);
          if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || Number.isNaN(date.getTime()))
            return msg.warning(`${f.label}日期格式不正确`);
        }
        if (f.kind === 'number' && value) {
          const number = Number(value);
          if (!Number.isFinite(number)) return msg.warning(`${f.label}必须为数字`);
          if (number < 0) return msg.warning(`${f.label}不能为负数`);
        }
      }
      if (typeId === 'defect' && textValue(r.data.status) === '已关闭') {
        if (!textValue(r.data.cause).trim()) return msg.warning('关闭不良单前必须填写原因分析');
        if (!textValue(r.data.correctiveAction).trim()) return msg.warning('关闭不良单前必须填写纠正措施');
      }
    }
    try {
      await qualityApi.saveBatch({
        type: typeId,
        categoryId,
        records: draft
          .filter((r) => !deleted.includes(r.id))
          .map((r) => ({
            id: r.newRow ? undefined : r.id,
            businessNo: r.businessNo,
            data: r.data,
            lockVersion: r.lockVersion,
          })),
        deleteIds: deleted.filter((id) => !id.startsWith('new-')),
      });
      msg.success('品管数据已保存');
      setEditing(false);
      setDirty(false);
      await Promise.all([loadRows(), loadCategories()]);
    } catch (e) {
      msg.error(e instanceof Error ? e.message : '保存失败');
    }
  };
  const categoryModal = (current?: QualityCategory) => {
    let name = current?.name || '',
      description = current?.description || '';
    Modal.confirm({
      title: current ? '编辑分类' : '新增分类',
      width: 520,
      content: (
        <Space direction="vertical" style={{ width: '100%', marginTop: 16 }}>
          <Input
            defaultValue={name}
            maxLength={20}
            placeholder="分类名称"
            onChange={(e) => (name = e.target.value)}
          />
          <Input.TextArea
            defaultValue={description}
            maxLength={80}
            placeholder="分类简介"
            onChange={(e) => (description = e.target.value)}
          />
        </Space>
      ),
      onOk: async () => {
        if (!name.trim() || !description.trim()) throw new Error('请填写分类名称和简介');
        if (current)
          await qualityApi.updateCategory(current.id, {
            name: name.trim(),
            description: description.trim(),
          });
        else
          await qualityApi.createCategory({
            type: typeId,
            name: name.trim(),
            description: description.trim(),
          });
        await loadCategories();
      },
    });
  };
  const fields = type?.fields || [];
  const allSelected = visibleRows.length > 0 && visibleRows.every((r) => selected.has(r.id));
  return (
    <div className="quality-page">
      {holder}
      <div className="quality-tabs">
        {types.map((t) => (
          <button
            key={t.id}
            className={`quality-tab${t.id === typeId ? ' active' : ''}`}
            onClick={() => switchType(t.id)}
          >
            {t.name}
          </button>
        ))}
      </div>
      <div className="quality-categories">
        {categories.map((c) => (
          <div
            key={c.id}
            className={`quality-category${c.id === categoryId ? ' active' : ''}`}
            onClick={() => {
              if (!guard()) return;
              setCategoryId(c.id);
              setFilters({});
              setEditing(false);
              setDirty(false);
              setPage(1);
            }}
          >
            <span className="quality-category-icon">
              <FolderOpenOutlined />
            </span>
            <span className="quality-category-content">
              <b className="quality-category-title">{c.name}</b>
              <small className="quality-category-desc">{c.description}</small>
              <span className="quality-category-footer">
                <strong>
                  {c.recordCount} <i>条数据</i>
                </strong>
                <span className="quality-category-actions">
                  <Button
                    type="link"
                    size="small"
                    onClick={(e) => {
                      e.stopPropagation();
                      categoryModal(c);
                    }}
                  >
                    编辑
                  </Button>
                  <Button
                    type="link"
                    size="small"
                    danger
                    onClick={(e) =>
                      void (async () => {
                        e.stopPropagation();
                        try {
                          await qualityApi.deleteCategory(c.id);
                          await loadCategories();
                        } catch {
                          msg.warning('该分类下存在数据，请先移动或删除数据');
                        }
                      })()
                    }
                  >
                    删除
                  </Button>
                </span>
              </span>
            </span>
          </div>
        ))}
        <div className="quality-category quality-add-category" onClick={() => categoryModal()}>
          <PlusOutlined />
          <b>新增分类</b>
          <small>创建自定义品管分类</small>
        </div>
      </div>
      <section className="quality-data-panel">
        <div className="quality-toolbar">
          <Space>
            <b>{categories.find((c) => c.id === categoryId)?.name}</b>
            <span>共 {total} 条</span>
          </Space>
          <Space wrap>
            <Input
              value={keyword}
              placeholder="搜索当前表格"
              onChange={(e) => {
                if (!guard()) return;
                setKeyword(e.target.value);
                setPage(1);
              }}
              style={{ width: 210 }}
            />
            {filterFields.map((field) => {
              const values = Array.from(
                new Set([
                  ...field.options,
                  ...activeRows.map((row) => textValue(row.data[field.key])).filter(Boolean),
                ]),
              );
              return (
                <Select
                  key={field.key}
                  allowClear
                  showSearch
                  placeholder={`筛选${field.label}`}
                  aria-label={`筛选${field.label}`}
                  value={filters[field.key] || undefined}
                  options={values.map((value) => ({ value, label: value }))}
                  onChange={(value) => {
                    if (!guard()) return;
                    setFilters((current) => ({ ...current, [field.key]: value || '' }));
                    setPage(1);
                  }}
                  style={{ width: 150 }}
                />
              );
            })}
            <Button
              onClick={() => {
                if (!guard()) return;
                setKeyword('');
                setFilters({});
                setPage(1);
              }}
            >
              重置筛选
            </Button>
            {editing ? (
              <>
                <Button type="primary" onClick={() => void save()}>
                  保存修改
                </Button>
                <Button
                  onClick={() => {
                    if (guard()) {
                      setEditing(false);
                      setDirty(false);
                    }
                  }}
                >
                  取消修改
                </Button>
                <Button onClick={addRow}>新增行</Button>
                <Button
                  onClick={() => {
                    const ids = Array.from(selected).filter((id) => !id.startsWith('new-'));
                    const candidates = categories.filter((category) => category.id !== categoryId);
                    if (!ids.length) {
                      msg.warning('请先选择需要移动的已有数据');
                      return;
                    }
                    if (!candidates.length) {
                      msg.warning('当前业务类型没有可移动的目标分类');
                      return;
                    }
                    const firstCandidate = candidates[0];
                    if (!firstCandidate) return;
                    let targetId = firstCandidate.id;
                    Modal.confirm({
                      title: '移动到其他分类',
                      content: (
                        <Select
                          style={{ width: '100%', marginTop: 16 }}
                          defaultValue={targetId}
                          options={candidates.map((category) => ({ value: category.id, label: category.name }))}
                          onChange={(value) => { targetId = value; }}
                        />
                      ),
                      onOk: async () => {
                        await qualityApi.move(ids, targetId);
                        msg.success('数据已移动到目标分类');
                        setSelected(new Set());
                        setEditing(false);
                        setDirty(false);
                        await Promise.all([loadRows(), loadCategories()]);
                      },
                    });
                  }}
                  disabled={!selected.size}
                >
                  移动分类
                </Button>
                <Button
                  onClick={() => {
                    const chosen = draft.filter((r) => selected.has(r.id));
                    setDraft((v) => [
                      ...v,
                      ...chosen.map((r) => {
                        const no = newNo();
                        return {
                          ...r,
                          id: `new-${crypto.randomUUID()}`,
                          businessNo: no,
                          data: { ...r.data, [idKey]: no },
                          newRow: true,
                        };
                      }),
                    ]);
                    setSelected(new Set());
                    setDirty(true);
                  }}
                  disabled={!selected.size}
                >
                  复制行
                </Button>
                <Button
                  danger
                  disabled={!selected.size}
                  onClick={() => {
                    setDeleted((v) => [...v, ...selected]);
                    setDraft((v) => v.filter((r) => !selected.has(r.id)));
                    setSelected(new Set());
                    setDirty(true);
                  }}
                >
                  删除选中行
                </Button>
                <span className="quality-dirty">{dirty ? '● 有未保存修改' : '尚未修改'}</span>
              </>
            ) : (
              <>
                <Button type="primary" onClick={activeRows.length ? startEdit : addRow}>
                  编辑表格
                </Button>
              </>
            )}
          </Space>
        </div>
        <div className="quality-grid-wrap">
          {visibleRows.length ? (
            <table className="quality-grid">
              <thead>
                <tr>
                  <th>#</th>
                  <th>
                    <Checkbox
                      checked={allSelected}
                      onChange={(e) =>
                        setSelected(
                          e.target.checked ? new Set(visibleRows.map((r) => r.id)) : new Set(),
                        )
                      }
                    />
                  </th>
                  {fields.map((f) => (
                    <th key={f.key}>
                      {f.label}
                      {f.required ? ' *' : ''}
                    </th>
                  ))}
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {visibleRows.map((r, i) => (
                  <tr key={r.id}>
                    <td>{i + 1}</td>
                    <td>
                      <Checkbox
                        checked={selected.has(r.id)}
                        onChange={(e) =>
                          setSelected((v) => {
                            const n = new Set(v);
                            if (e.target.checked) n.add(r.id);
                            else n.delete(r.id);
                            return n;
                          })
                        }
                      />
                    </td>
                    {fields.map((f) => (
                      <td key={f.key}>
                        {editing ? (
                          f.kind === 'select' ? (
                            <Select
                              value={textValue(r.data[f.key])}
                              onChange={(v) => update(draft.findIndex((item) => item.id === r.id), f.key, v)}
                              options={f.options.map((x) => ({ value: x, label: x }))}
                            />
                          ) : f.kind === 'long' ? (
                            <textarea
                              value={textValue(r.data[f.key])}
                              onChange={(e) => update(draft.findIndex((item) => item.id === r.id), f.key, e.target.value)}
                            />
                          ) : (
                            <input
                              type={
                                f.kind === 'date' ? 'date' : f.kind === 'number' ? 'number' : 'text'
                              }
                              disabled={!r.newRow && f.key === idKey}
                              value={textValue(r.data[f.key])}
                              onChange={(e) => update(draft.findIndex((item) => item.id === r.id), f.key, e.target.value)}
                            />
                          )
                        ) : (
                          textValue(r.data[f.key], '—')
                        )}
                      </td>
                    ))}
                    <td>
                      <Button type="link" onClick={() => navigate(`/quality/records/${r.id}`)}>
                        查看
                      </Button>
                      {typeId === 'record' && textValue(r.data.judgement) === '不合格' && !editing && (
                        <Button
                          type="link"
                          onClick={() =>
                            void qualityApi.createDefectFromRecord(r.id).then(
                              () => msg.success('已生成不良报告待处理记录'),
                              (e) => msg.error(e instanceof Error ? e.message : '生成不良报告失败'),
                            )
                          }
                        >
                          生成不良报告
                        </Button>
                      )}
                      <Button
                        type="link"
                        danger
                        icon={typeId === 'standard' ? undefined : <DeleteOutlined />}
                        disabled={r.newRow || editing}
                        onClick={() =>
                          void (async () => {
                            if (
                              !window.confirm(
                                '确定要删除这条品管数据吗？删除后数据将从列表中隐藏。',
                              )
                            ) {
                              return;
                            }
                            try {
                              await qualityApi.deleteRecord(r.id);
                              msg.success('品管数据已删除');
                              await Promise.all([loadRows(), loadCategories()]);
                            } catch (e) {
                              msg.error(e instanceof Error ? e.message : '删除失败');
                            }
                          })()
                        }
                      >
                        删除
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <Empty style={{ marginTop: 100 }} description="当前分类暂无符合条件的数据" />
          )}
        </div>
        <div className="quality-footer">
          <Pagination
            current={page}
            pageSize={size}
            total={total}
            showSizeChanger
            onChange={(p, s) => {
              if (!guard()) return;
              setPage(p);
              setSize(s);
            }}
          />
        </div>
      </section>
    </div>
  );
}
