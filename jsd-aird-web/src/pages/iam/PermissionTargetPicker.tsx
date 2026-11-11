import { Select, Typography } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';

import { dataApi } from '@/services/data/data-api';
import { listExperimentImports } from '@/services/experiments/experiment-api';
import { qualityApi } from '@/services/quality/quality-api';
import { productionUploadApi } from '@/services/production-orders/production-upload-api';
import { inventoryApi } from '@/services/inventory/inventory-api';
import { knowledgeApi } from '@/services/knowledge/knowledge-api';
import { getPartner, getPartners } from '@/services/partners/partner-api';
import { getProject, getProjects } from '@/services/project/project-api';
import { spectrumApi } from '@/services/spectrum/spectrum-api';
import { templateApi } from '@/services/templates/template-api';
import { getResearchTest, listResearchTests, listResearchTestUploads } from '@/services/research-test/research-test-api';

type TargetScope = 'PROJECT' | 'CATEGORY' | 'SELECTED';
type TargetSource = 'project' | 'template' | 'knowledge' | 'data' | 'spectrum' | 'customer' | 'test-standard' | 'inventory' | 'inventory-ledger-rnd' | 'inventory-ledger-production' | 'inventory-sample' | 'inventory-shipment';

interface TargetOption {
  value: string;
  label: string;
}

interface PermissionTargetPickerProps {
  permissionCode: string;
  /** Module of the functional row; falls back to the permission code prefix. */
  module?: string;
  scopeType: TargetScope;
  value: string[];
  onChange: (value: string[]) => void;
  allowedTargetIds?: string[];
}

const KNOWN_SOURCES: TargetSource[] = ['project', 'template', 'knowledge', 'data', 'spectrum', 'customer', 'inventory'];

const optionsFrom = (items: Array<{ id: string; name?: string; title?: string; displayName?: string }>) =>
  items.map((item) => ({
    value: item.id,
    label: item.name || item.title || item.displayName || item.id,
  }));

const resolveSource = (module: string | undefined, permissionCode: string): TargetSource => {
  if (permissionCode.startsWith('research-test.standard.')) return 'test-standard';
  if (permissionCode.startsWith('inventory.rnd.')) return 'inventory-ledger-rnd';
  if (permissionCode.startsWith('inventory.production.')) return 'inventory-ledger-production';
  if (permissionCode.startsWith('inventory.sample.')) return 'inventory-sample';
  if (permissionCode.startsWith('inventory.shipment.')) return 'inventory-shipment';
  const candidate = (module || permissionCode.split('.')[0] || '').toLowerCase();
  if (candidate === 'inventory.rnd') return 'inventory-ledger-rnd';
  if (candidate === 'inventory.production') return 'inventory-ledger-production';
  if (candidate === 'inventory.sample') return 'inventory-sample';
  if (candidate === 'inventory.shipment') return 'inventory-shipment';
  return KNOWN_SOURCES.find((item) => item === candidate) || 'project';
};

export function PermissionTargetPicker({
  permissionCode,
  module,
  scopeType,
  value,
  onChange,
  allowedTargetIds,
}: PermissionTargetPickerProps) {
  const [options, setOptions] = useState<TargetOption[]>([]);
  const [loading, setLoading] = useState(false);
  const loadingRef = useRef(false);
  const loadedRef = useRef(false);
  const source = resolveSource(module, permissionCode);

  const loadOptions = async () => {
    if (loadedRef.current || loadingRef.current) return;
    loadingRef.current = true;
    setLoading(true);
    try {
      let next: TargetOption[] = [];
      if (scopeType === 'PROJECT') {
        const page = await getProjects({ size: 100 });
        next = page.items.map((item) => ({ value: item.id, label: `${item.projectCode} ${item.name}` }));
      } else if (scopeType === 'CATEGORY') {
        if (source === 'knowledge') next = optionsFrom(await knowledgeApi.categories());
        else if (source === 'data') next = optionsFrom(await dataApi.listCategories());
        else if (source === 'spectrum') next = optionsFrom(await spectrumApi.categories());
        else next = optionsFrom(await templateApi.listCategories());
      } else if (permissionCode.startsWith('template.upload.')) {
        next = optionsFrom(await templateApi.listImportTargets());
      } else if (permissionCode.startsWith('eln.upload.')) {
        const [free, imports] = await Promise.all([
          listExperimentImports(), dataApi.listExperimentJobs({ page: 1, size: 100 }),
        ]);
        const more = await Promise.all(Array.from({ length: imports.totalPages - 1 }, (_, index) =>
          dataApi.listExperimentJobs({ page: index + 2, size: 100 })));
        next = [...free.map((item) => ({ value: item.id, label: item.sourceFileName || item.id })),
          ...[imports, ...more].flatMap((page) => page.items.map((item) =>
            ({ value: item.id, label: item.sourceFileName || item.id })))];
      } else if (permissionCode.startsWith('research-test.report.upload.') || permissionCode.startsWith('research-test.standard.upload.')) {
        const type = permissionCode.startsWith('research-test.report.') ? 'REPORT' : 'STANDARD';
        const first = await listResearchTestUploads(type, { page: 1, size: 100 });
        const pages = await Promise.all(Array.from({ length: first.totalPages - 1 }, (_, index) =>
          listResearchTestUploads(type, { page: index + 2, size: 100 })));
        next = [first, ...pages].flatMap((page) => page.items.map((item) =>
          ({ value: item.id, label: item.originalName || item.id })));
      } else if (permissionCode.startsWith('quality.upload.')) {
        const first = await qualityApi.uploads({ page: 1, size: 100 });
        const pages = await Promise.all(Array.from({ length: first.totalPages - 1 }, (_, index) =>
          qualityApi.uploads({ page: index + 2, size: 100 })));
        next = [first, ...pages].flatMap((page) => page.items.map((item) =>
          ({ value: item.id, label: item.originalName || item.id })));
      } else if (permissionCode.startsWith('production.upload.')) {
        const first = await productionUploadApi.list({ page: 1, size: 100 });
        const pages = await Promise.all(Array.from({ length: first.totalPages - 1 }, (_, index) =>
          productionUploadApi.list({ page: index + 2, size: 100 })));
        next = [first, ...pages].flatMap((page) => page.items.map((item) =>
          ({ value: item.id, label: item.originalName || item.id })));
      } else if (source === 'template') {
        const page = await templateApi.list({ size: 100 });
        next = page.items.map((item) => ({ value: item.templateId, label: item.name || item.templateCode || item.templateId }));
      } else if (source === 'knowledge') {
        const page = await knowledgeApi.list({ size: 100 });
        next = page.items.map((item) => ({ value: item.id, label: item.title || item.id }));
      } else if (source === 'data') {
        const page = await dataApi.listJobs({ size: 100 });
        next = page.items.map((item) => ({ value: item.id, label: item.sourceFileName || item.id }));
      } else if (source === 'spectrum') {
        const page = await spectrumApi.listCharts({ size: 100 });
        next = page.items.map((item) => ({ value: item.id, label: item.title || item.id }));
      } else if (source === 'customer') {
        const page = await getPartners({ status: 'ACTIVE', page: 1, size: 100 });
        next = page.items.map((item) => ({ value: item.id, label: `${item.partnerCode} ${item.name}` }));
      } else if (source === 'inventory') {
        const items = await inventoryApi.productOptions();
        next = items.map((item) => ({ value: item.id, label: `${item.code} ${item.name}` }));
      } else if (source === 'inventory-ledger-rnd' || source === 'inventory-ledger-production') {
        const scope = source === 'inventory-ledger-rnd' ? 'RND' : 'PRODUCTION';
        const first = await inventoryApi.transactions({ scope, page: 1, size: 100 });
        const pages = await Promise.all(Array.from({ length: first.totalPages - 1 }, (_, index) =>
          inventoryApi.transactions({ scope, page: index + 2, size: 100 })));
        next = [first, ...pages].flatMap(page => page.items.map(item => ({
          value: item.id, label: `${item.documentNo} ${item.productName}`,
        })));
      } else if (source === 'inventory-sample') {
        const first = await inventoryApi.samples({ page: 1, size: 100 });
        const pages = await Promise.all(Array.from({ length: first.totalPages - 1 }, (_, index) =>
          inventoryApi.samples({ page: index + 2, size: 100 })));
        next = [first, ...pages].flatMap(page => page.items.map(item => ({
          value: item.id, label: `${item.dispatchNo} ${item.productName}`,
        })));
      } else if (source === 'inventory-shipment') {
        const first = await inventoryApi.shipmentHeaders({ page: 1, size: 100 });
        const pages = await Promise.all(Array.from({ length: first.totalPages - 1 }, (_, index) =>
          inventoryApi.shipmentHeaders({ page: index + 2, size: 100 })));
        next = [first, ...pages].flatMap(page => page.items.map(item => ({
          value: item.id, label: item.shipmentNo,
        })));
      } else if (source === 'test-standard') {
        const first = await listResearchTests('STANDARD', { page: 1, size: 100 });
        const pages = await Promise.all(Array.from({ length: first.totalPages - 1 }, (_, index) =>
          listResearchTests('STANDARD', { page: index + 2, size: 100 })));
        next = [first, ...pages].flatMap((page) => page.items.map((item) =>
          ({ value: item.id, label: `${item.businessNo} ${item.name}` })));
      } else {
        const page = await getProjects({ size: 100 });
        next = page.items.map((item) => ({ value: item.id, label: `${item.projectCode} ${item.name}` }));
      }
      const missingProjectIds = scopeType === 'PROJECT' || (scopeType === 'SELECTED' && source === 'project')
        ? value.filter((id) => !next.some((option) => option.value === id)) : [];
      if (missingProjectIds.length) {
        const selectedProjects = await Promise.allSettled(missingProjectIds.map((id) => getProject(id)));
        selectedProjects.forEach((result) => {
          if (result.status === 'fulfilled') {
            next.push({ value: result.value.id, label: `${result.value.projectCode} ${result.value.name}` });
          }
        });
      }
      if (source === 'test-standard') {
        const missingIds = value.filter((id) => !next.some((option) => option.value === id));
        const selectedStandards = await Promise.allSettled(missingIds.map((id) => getResearchTest('STANDARD', id)));
        selectedStandards.forEach((result) => {
          if (result.status === 'fulfilled') {
            const item = result.value.summary;
            next.push({ value: item.id, label: `${item.businessNo} ${item.name}` });
          }
        });
      }
      if (source === 'customer') {
        const missingIds = value.filter((id) => !next.some((option) => option.value === id));
        const selectedPartners = await Promise.allSettled(missingIds.map(getPartner));
        selectedPartners.forEach((result) => {
          if (result.status === 'fulfilled') {
            const item = result.value;
            next.push({ value: item.id, label: `${item.partnerCode} ${item.name}` });
          }
        });
      }
      if (source === 'knowledge' || source === 'data') {
        const missingIds = value.filter((id) => !next.some((option) => option.value === id));
        const selectedItems = await Promise.allSettled(missingIds.map((id) => source === 'knowledge' ? knowledgeApi.get(id) : dataApi.getJob(id)));
        selectedItems.forEach((result) => {
          if (result.status === 'fulfilled') {
            const item = result.value;
            next.push({ value: item.id, label: 'title' in item ? item.title || item.id : item.sourceFileName || item.id });
          }
        });
      }
      if (source === 'spectrum') {
        const missingIds = value.filter((id) => !next.some((option) => option.value === id));
        const selectedCharts = await Promise.allSettled(missingIds.map((id) => spectrumApi.chart(id)));
        selectedCharts.forEach((result) => {
          if (result.status === 'fulfilled') {
            const item = result.value;
            next.push({ value: item.id, label: item.title || item.id });
          }
        });
      }
      setOptions(allowedTargetIds ? next.filter((item) => allowedTargetIds.includes(item.value)) : next);
      loadedRef.current = true;
    } catch {
      setOptions([]);
    } finally {
      loadingRef.current = false;
      setLoading(false);
    }
  };

  useEffect(() => {
    if (value.length) void loadOptions();
  }, []);

  const inventorySource = source.startsWith('inventory');
  const invalidInventoryIds = inventorySource && loadedRef.current
    ? value.filter((id) => !options.some((option) => option.value === id))
    : [];
  const mergedOptions = useMemo(() => [
    ...(!inventorySource && !allowedTargetIds ? value.filter((id) => !options.some((option) => option.value === id)).map((id) => ({ value: id, label: id })) : []),
    ...options,
  ], [allowedTargetIds, inventorySource, options, value]);
  const uploadFileSource = ['eln.upload.', 'research-test.report.upload.', 'research-test.standard.upload.', 'quality.upload.', 'production.upload.', 'template.upload.']
    .some((prefix) => permissionCode.startsWith(prefix));
  const placeholder = uploadFileSource ? '选择文件' : scopeType === 'PROJECT' ? '选择项目'
    : scopeType === 'CATEGORY' ? '选择分类'
      : source === 'template' ? '选择模板'
        : source === 'knowledge' ? '选择资料'
          : source === 'data' ? '选择数据'
            : source === 'spectrum' ? '选择图谱'
              : source === 'customer' ? '选择客户'
                : source === 'inventory' ? '选择库存产品'
                  : source === 'inventory-ledger-rnd' || source === 'inventory-ledger-production' ? '选择库存流水'
                    : source === 'inventory-sample' ? '选择发样记录'
                      : source === 'inventory-shipment' ? '选择出货单'
                  : source === 'test-standard' ? '选择测试标准'
                  : '选择项目';
  const help = uploadFileSource ? '仅授权所选上传文件' : scopeType === 'PROJECT' ? '仅授权所选项目及其关联数据'
    : scopeType === 'CATEGORY' ? '仅授权所选分类及其子分类'
      : source === 'template' ? '仅授权所选模板'
        : source === 'knowledge' ? '仅授权所选资料'
          : source === 'data' ? '仅授权所选数据'
            : source === 'spectrum' ? '仅授权所选图谱及其关联数据'
              : source === 'customer' ? '仅授权所选客户及其关联数据'
                : source === 'inventory' ? '仅授权所选库存产品'
                  : source === 'inventory-ledger-rnd' || source === 'inventory-ledger-production' ? '仅授权所选库存流水'
                    : source === 'inventory-sample' ? '仅授权所选发样记录'
                      : source === 'inventory-shipment' ? '仅授权所选出货单'
                  : source === 'test-standard' ? '仅授权所选测试标准'
                  : '仅授权所选对象';

  return (
    <div className="iam-scope-target-picker">
      <Select
        mode="multiple"
        size="small"
        value={(inventorySource || allowedTargetIds) && loadedRef.current ? value.filter((id) => options.some((option) => option.value === id)) : value}
        placeholder={placeholder}
        options={mergedOptions}
        loading={loading}
        showSearch
        optionFilterProp="label"
        maxTagCount={2}
        maxTagTextLength={24}
        popupMatchSelectWidth={false}
        listHeight={320}
        classNames={{ popup: { root: 'iam-scope-target-dropdown' } }}
        onOpenChange={(open) => {
          if (open) void loadOptions();
        }}
        onChange={(ids) => onChange(inventorySource || allowedTargetIds ? ids.filter((id) => options.some((option) => option.value === id)) : ids)}
        style={{ width: '100%', minWidth: 260 }}
      />
      <Typography.Text type="secondary" className="iam-scope-target-help">
        {help}
      </Typography.Text>
      {invalidInventoryIds.length > 0 && (
        <Typography.Text type="danger" className="iam-scope-target-help">
          {invalidInventoryIds.length} 条旧授权不属于当前页面，请重新选择并保存
        </Typography.Text>
      )}
    </div>
  );
}
