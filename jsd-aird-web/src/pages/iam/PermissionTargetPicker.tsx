import { Select, Typography } from 'antd';
import { useMemo, useRef, useState } from 'react';

import { dataApi } from '@/services/data/data-api';
import { knowledgeApi } from '@/services/knowledge/knowledge-api';
import { getProject, getProjects } from '@/services/project/project-api';
import { spectrumApi } from '@/services/spectrum/spectrum-api';
import { templateApi } from '@/services/templates/template-api';

type TargetScope = 'PROJECT' | 'CATEGORY' | 'SELECTED';

interface TargetOption {
  value: string;
  label: string;
}

interface PermissionTargetPickerProps {
  permissionCode: string;
  scopeType: TargetScope;
  value: string[];
  onChange: (value: string[]) => void;
}

const isModule = (code: string, module: string) => code.startsWith(`${module}.`);

const optionsFrom = (items: Array<{ id: string; name?: string; title?: string; displayName?: string }>) =>
  items.map((item) => ({
    value: item.id,
    label: item.name || item.title || item.displayName || item.id,
  }));

export function PermissionTargetPicker({
  permissionCode,
  scopeType,
  value,
  onChange,
}: PermissionTargetPickerProps) {
  const [options, setOptions] = useState<TargetOption[]>([]);
  const [loading, setLoading] = useState(false);
  const loadingRef = useRef(false);
  const loadedRef = useRef(false);

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
        if (isModule(permissionCode, 'template')) {
          next = optionsFrom(await templateApi.listCategories());
        } else if (isModule(permissionCode, 'knowledge')) {
          next = optionsFrom(await knowledgeApi.categories());
        } else if (isModule(permissionCode, 'data')) {
          next = optionsFrom(await dataApi.listCategories());
        } else if (isModule(permissionCode, 'spectrum')) {
          next = optionsFrom(await spectrumApi.categories());
        } else {
          next = optionsFrom(await templateApi.listCategories());
        }
      } else if (isModule(permissionCode, 'template')) {
        const page = await templateApi.list({ size: 100 });
        next = page.items.map((item) => ({ value: item.templateId, label: item.name || item.templateCode || item.templateId }));
      } else if (isModule(permissionCode, 'knowledge')) {
        const page = await knowledgeApi.list({ size: 100 });
        next = page.items.map((item) => ({ value: item.id, label: item.title || item.id }));
      } else if (isModule(permissionCode, 'data')) {
        const page = await dataApi.listJobs({ size: 100 });
        next = page.items.map((item) => ({ value: item.id, label: item.sourceFileName || item.id }));
      } else if (isModule(permissionCode, 'spectrum')) {
        const page = await spectrumApi.listCharts({ size: 100 });
        next = page.items.map((item) => ({ value: item.id, label: item.title || item.id }));
      } else {
        const page = await getProjects({ size: 100 });
        next = page.items.map((item) => ({ value: item.id, label: `${item.projectCode} ${item.name}` }));
      }
      const missingProjectIds = (scopeType === 'PROJECT' || (!isModule(permissionCode, 'template')
        && !isModule(permissionCode, 'knowledge') && !isModule(permissionCode, 'data')
        && !isModule(permissionCode, 'spectrum')))
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
  const placeholder = scopeType === 'PROJECT' ? '选择项目' : scopeType === 'CATEGORY' ? '选择分类' : '选择对象';
  const help = scopeType === 'PROJECT' ? '仅授权所选项目及其关联数据' : scopeType === 'CATEGORY' ? '仅授权所选分类及其子分类' : '仅授权所选对象';

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
