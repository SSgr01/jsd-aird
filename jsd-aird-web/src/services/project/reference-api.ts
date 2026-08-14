export interface ReferenceMaterial {
  id: string;
  type: 'PDF' | 'ARTICLE' | 'SUGGESTION' | 'TECH_PATH';
  typeLabel: string;
  title: string;
  summary: string;
  source: string;
  addedBy: string;
  addedAt: string;
  status: 'ACTIVE' | 'REMOVED';
  originalUrl?: string;
  sourceUrl?: string;
}

export interface ReferenceMaterialQuery {
  keyword?: string;
  source?: string;
  stage?: string;
  addedBy?: string;
  status?: string;
  page?: number;
  size?: number;
}

export const REFERENCE_SOURCE_OPTIONS = [
  { value: '研发知识库', label: '研发知识库' },
  { value: '实验数据库', label: '实验数据库' },
  { value: '外部文献', label: '外部文献' },
  { value: 'AI建议', label: 'AI建议' },
];

export const REFERENCE_STAGE_OPTIONS = [
  { value: '需求输入', label: '需求输入' },
  { value: '研发准备与立项', label: '研发准备与立项' },
  { value: '实验验证', label: '实验验证' },
  { value: '测试验证', label: '测试验证' },
  { value: '放大与量产', label: '放大与量产' },
];

export const REFERENCE_STATUS_OPTIONS = [
  { value: 'ACTIVE', label: '有效' },
  { value: 'REMOVED', label: '已移除' },
];

const MOCK_DATA: ReferenceMaterial[] = [
  {
    id: '1',
    type: 'PDF',
    typeLabel: 'PDF',
    title: 'PET光学深化膜产品说明书.pdf',
    summary: '包含PET光学深化膜的产品参数、适用场景及耐水煮、附着力等关键性能说明。',
    source: '研发知识库',
    addedBy: '张伟',
    addedAt: '2025-07-11T14:23:00',
    status: 'ACTIVE',
    originalUrl: '#',
  },
  {
    id: '2',
    type: 'ARTICLE',
    typeLabel: '文献',
    title: 'UV-Curable Coatings for PET Substrates',
    summary: '研究UV固化涂层在PET基材上的应用性能，重点包括耐候性、附着力与耐水煮性能。',
    source: '实验数据库',
    addedBy: '李想',
    addedAt: '2025-05-20T09:18:00',
    status: 'ACTIVE',
    originalUrl: '#',
    sourceUrl: '#',
  },
  {
    id: '3',
    type: 'SUGGESTION',
    typeLabel: '建议',
    title: '附着力测试条件建议',
    summary: 'PET基材表面活化度对粘结性能影响显著，建议先进行电晕处理，再进行附着力验证测试。',
    source: '研发知识库',
    addedBy: '张伟',
    addedAt: '2025-07-10T16:45:00',
    status: 'ACTIVE',
    sourceUrl: '#',
  },
  {
    id: '4',
    type: 'TECH_PATH',
    typeLabel: '技术路径',
    title: '提高耐水煮性能技术路径',
    summary: '建议从表面改性和涂层配方双维度优化：提高交联密度并添加耐水单体，以提升整体耐水煮性能。',
    source: '研发知识库',
    addedBy: '王敏',
    addedAt: '2025-07-10T11:32:00',
    status: 'ACTIVE',
    sourceUrl: '#',
  },
];

interface PageData<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export async function listReferenceMaterials(
  _projectId: string,
  query: ReferenceMaterialQuery,
): Promise<PageData<ReferenceMaterial>> {
  // TODO: 接入后端 /projects/{projectId}/references
  await new Promise((resolve) => setTimeout(resolve, 200));

  let items = [...MOCK_DATA];

  if (query.keyword) {
    const kw = query.keyword.toLowerCase();
    items = items.filter(
      (item) =>
        item.title.toLowerCase().includes(kw) ||
        item.summary.toLowerCase().includes(kw) ||
        item.source.toLowerCase().includes(kw),
    );
  }

  if (query.source) {
    items = items.filter((item) => item.source === query.source);
  }

  if (query.status) {
    items = items.filter((item) => item.status === query.status);
  }

  return {
    items,
    page: query.page ?? 1,
    size: query.size ?? 50,
    total: items.length,
    totalPages: 1,
  };
}

export async function removeReferenceMaterial(_projectId: string, _id: string): Promise<void> {
  // TODO: 接入后端删除接口
  await new Promise((resolve) => setTimeout(resolve, 150));
}
