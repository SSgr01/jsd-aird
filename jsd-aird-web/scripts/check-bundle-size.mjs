import { bundleReport } from './bundle-report.mjs';

const report = await bundleReport();
const budgets = [
  ['公共启动资源', report.startup, 900_000, 280_000],
  ['登录页', report.pages['src/pages/auth/LoginPage.tsx'], 1_200_000, 380_000],
  ['项目列表', report.pages['src/pages/project/ProjectListPage.tsx'], 1_950_000, 640_000],
  ['实验列表', report.pages['src/pages/experiments/ExperimentListPage.tsx'], 2_100_000, 680_000],
  ['知识库列表', report.pages['src/pages/knowledge/KnowledgeViewPage.tsx'], 1_950_000, 640_000],
];
for (const [label, size, maxBytes, maxGzipBytes] of budgets) {
  if (!size) throw new Error(`未找到 ${label} 的构建记录，请检查路由是否仍按页面拆分`);
  if (size.bytes > maxBytes || size.gzipBytes > maxGzipBytes) {
    throw new Error(`${label} 超过资源预算: ${size.bytes}/${maxBytes} bytes, gzip ${size.gzipBytes}/${maxGzipBytes} bytes`);
  }
  console.log(`Bundle gate passed: ${label}: ${Math.round(size.bytes / 1000)} KB, gzip ${Math.round(size.gzipBytes / 1000)} KB`);
}
