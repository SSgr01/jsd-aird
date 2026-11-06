import { Select, Typography } from 'antd';
import { useMemo, useRef, useState } from 'react';

import { dataApi } from '@/services/data/data-api';
import { knowledgeApi } from '@/services/knowledge/knowledge-api';
import { getPartners } from '@/services/partners/partner-api';
import { getProject, getProjects } from '@/services/project/project-api';
import { spectrumApi } from '@/services/spectrum/spectrum-api';
import { templateApi } from '@/services/templates/template-api';

type TargetScope = 'PROJECT' | 'CATEGORY' | 'SELECTED';
type TargetSource = 'project' | 'template' | 'knowledge' | 'data' | 'spectrum' | 'customer';

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
}

const KNOWN_SOURCES: TargetSource[] = ['project', 'template', 'knowledge', 'data', 'spectrum', 'customer'];

const optionsFrom = (items: Array<{ id: string; name?: string; title?: string; displayName?: string }>) =>
  items.map((item) => ({
    value: item.id,
    label: item.name || item.title || item.displayName || item.id,
  }));

const resolveSource = (module: string | undefined, permissionCode: string): TargetSource => {
  const candidate = (module || permissionCode.split('.')[0] || '').toLowerCase();
  return KNOWN_SOURCES.find((item) => item === candidate) || 'project';
};

export function PermissionTargetPicker({
  permissionCode,
  module,
  scopeType,
  value,
  onChange,
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
      } else {
        const page = await getProjects({ size: 100 });
        next = page.items.map((item) => ({ value: item.id, label: `${item.projectCode} ${item.name}` }));
      }
      const missingProjectIds = scopeType === 'PROJECT' || (scopeType === 'SELECTED' && source === 'project')
        ? value.filter((id) => !next.some((option) => option.value === id))
        : [];
      if (missingProjectIds.length) {
        const selectedProjects = await Promise.allSettled(missingProjectIds.map((id) => getProject(id)));
        selectedProjects.forEach((result) => {
          if (result.status === 'fulfilled') {
            next.push({ value: result.value.id, label: `${result.value.projectCode} ${result.value.name}` });
          }
        });
      }
      setOptions(next);
      loadedRef.current = true;
    } catch {
      setOptions([]);
    } finally {
      loadingRef.current = false;
      setLoading(false);
    }
  };

  const mergedOptions = useMemo(() => [
    ...value.filter((id) => !options.some((option) => option.value === id)).map((id) => ({ value: id, label: id })),
    ...options,
  ], [options, value]);
  const placeholder = scopeType === 'PROJECT' ? '选择项目'
    : scopeType === 'CATEGORY' ? '选择分类'
      : source === 'template' ? '选择模板'
        : source === 'knowledge' ? '选择资料'
          : source === 'data' ? '选择数据'
            : source === 'spectrum' ? '选择图谱'
              : source === 'customer' ? '选择客户'
                : '选择项目';
  const help = scopeType === 'PROJECT' ? '仅授权所选项目及其关联数据'
    : scopeType === 'CATEGORY' ? '仅授权所选分类及其子分类'
      : source === 'template' ? '仅授权所选模板'
        : source === 'knowledge' ? '仅授权所选资料'
          : source === 'data' ? '仅授权所选数据'
            : source === 'spectrum' ? '仅授权所选图谱及其关联数据'
              : source === 'customer' ? '仅授权所选客户及其关联数据'
                : '仅授权所选对象';

  return (
    <div className="iam-scope-target-picker">
      <Select
        mode="multiple"
        size="small"
        value={value}
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
        onChange={onChange}
        style={{ width: '100%', minWidth: 260 }}
      />
      <Typography.Text type="secondary" className="iam-scope-target-help">
        {help}
      </Typography.Text>
    </div>
  );
}
