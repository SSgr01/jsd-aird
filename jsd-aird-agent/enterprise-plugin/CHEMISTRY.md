# 化学 Markdown

支持 `$\ce{H2O}$`、`$$\ce{A ->[h\nu] B}$$`，以及 `smiles` / `reaction-smiles` 围栏代码块。原生聊天在回复完成后绘制结构；普通业务页面在围栏闭合后绘制。

RDKit 2026.9.2 使用本地 WebAssembly，在独立 Worker 中运行，不调用外部服务。支持下载 SVG、按结构坐标生成画布、适应宽度与放大滚动查看。标签语法为 `名称:SMILES` 或 `名称：SMILES`，分隔符两侧空格可选。名称原样保留，支持 A、B、BDB、ESBO、中文、方括号，无 120 字符截断；受整组资源预算约束。没有名称时不推测化学名称。单行/多行命名结构使用 YAML 围栏；`smiles`/`reaction-smiles` 的单行作为完整结构，名称可用围栏的 `name="…"` 元数据指定，多行兼容命名列表。渲染入口将 format=labels/raw 明确传给共享 viewer，避免将芳香键或原子映射的冒号当作名称分隔符，不根据名称是否像化学符号区别处理。普通 YAML 保留代码显示。

资源预算集中在 `jsd-aird-web/public/chemistry/limits.json`：默认整组 65536 字符、单结构 32768 字符、100 个结构；加载和单结构绘图各 45 秒，整组 180 秒，单 SVG 8MB。加载与绘图分开计时，逐结构返回结果；单张超时仅重建 Worker，已完成的图保留，其余结构继续，可单独重试失败项。部署时修改配置后重新打包插件资源。不能从 PDF 图片自动识别结构，解析成功也不等于已核对文献或验证反应机理。

构建：先安装 jsd-aird-web 的 npm 依赖，再运行 `node jsd-aird-agent/enterprise-plugin/build.mjs`。该步骤准备自托管资源并幂等应用 `patches/chemistry-markdown.patch` 到固定版本 Harness。共享解析源为 `jsd-aird-web/src/components/markdown/chemistry-input.ts`；准备脚本生成 viewer 的 input.js，Harness 补丁携带同源副本并检查一致性。更改源文件时同步更新补丁。之后重新编译 ui-primitives 的 TypeScript 产物、客户端库和 **apps/web 网页主包**（`build:web`），更新 Client 构建记录。最后刷新页面；若后端插件或静态 HTML 被服务缓存，再重启原生聊天服务。更新上游时若补丁不匹配会明确失败。

前端 `npm run dev` / `npm run build` 自动准备 RDKit 静态资源。WASM 约 7.4 MB，仅显示结构时加载，不进入普通聊天 JavaScript 主包。每个结构视图使用独立 Worker，关闭视图会释放内存。

RDKit 为 BSD-3-Clause；上游许可证：https://github.com/rdkit/rdkit/blob/master/license.txt 。随部署应保留 RDKit 版权和许可证文件。

## 证据图片读取

`evidence_image_read(view=auto)` 优先返回授权文档版本的独立原图；没有独立图时返回页面概览。`view=page` 强制概览，`view=detail` 强制高清 PDF 页。必须再调用原生 `read_image` 才能将实际图片交给视觉模型。按需高清读取，仍遵守每轮先读 1～3 张相关图的现有策略。

后端 `app.research-images` 支持环境变量或 Spring 配置覆盖，默认 PDF 输入 512MB、图片输入 128MB、输出 8MB；概览 200 DPI / 长边 2600px，高清 400 DPI / 长边 4800px。原图上限 1 亿像素，渲染/解码预算 2400 万像素。输入流落临时文件，PDFBox 使用文件随机读取及磁盘 scratch，结束时清理。输入预算和输出预算独立，大原图优先在保留尺寸下转成高质量 JPEG，仍超预算时降采样；返回 image 字段包含实际宽高、DPI、原始尺寸、resized 和 lossy，不能将预览宣称为未压缩原图。超出输入/像素预算仍明确拒绝，不无限制占用内存。
