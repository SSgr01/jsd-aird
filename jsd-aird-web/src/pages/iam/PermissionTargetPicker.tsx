import { Select, Typography } from 'antd';
import { useState } from 'react';

import { dataApi } from '@/services/data/data-api';
import { knowledgeApi } from '@/services/knowledge/knowledge-api';
import { getProjects } from '@/services/project/project-api';
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
  const [loaded, setLoaded] = useState(false);

  const loadOptions = async () => {
    if (loaded || loading) return;
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
      setOptions(next);
      setLoaded(true);
    } catch {
      setOptions([]);
    } finally {
      setLoading(false);
    }
  };

  const mergedOptions = [
    ...value.filter((id) => !options.some((option) => option.value === id)).map((id) => ({ value: id, label: id })),
    ...options,
  ];
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
        maxTagCount="responsive"
        onOpenChange={(open) => {
          if (open) void loadOptions();
        }}
        onChange={onChange}
        style={{ minWidth: 220 }}
      />
      <Typography.Text type="secondary" className="iam-scope-target-help">
        {help}
      </Typography.Text>
    </div>
  );
}
