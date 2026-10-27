# 实施与验证记录

两套固定编码为 `EXP_TEST_UNIFIED`、`EXP_SYNTHESIS_UNIFIED`。空白 XLSX 的原始字节复制为程序资源；示例记录只在测试资源中使用。模板安装不会初始化模拟实验记录，也不重建或覆盖四份原始 Excel。真实页面验收使用单独标记的本地实验草稿，不审核发布或加入训练集。

## 数据结构与操作

- 公共版本继续保存 Univer 快照、字段模型、映射及 V9 导入契约。工作表 `custom.labTemplate` 保存版本化执行定义；行列自定义元数据保存字段、物料行和对照组的固定 ID。
- 实验 `editModel.templateInstance` 保存实例定义、样品顺序、物料行、有效绑定和待确认问题。服务端从完整工作簿统一生成 `formulaItems`、`processSteps`、`testResults` 和 `dynamicValues`。
- 结构扩展使用 Univer 原生插行、插列、既有重复区域扩展与“新增字段”；三个实验专用入口已移除。区域配置解释新增记录、列表条目与业务字段，统一迁移字段模型、嵌套定位、记录/条目 ID 及公式范围。无字段对应的填写内容可以保存为草稿，并阻止确认。
- 合成投料按行保存 `feedSequence`，同一物料可分多次投料。建模按材料汇总 g，用量不冒充百分比；完整配方另存派生质量百分比。
- 临时项目立即可保存、重新打开和打印，默认不进入训练指标。从实验“另存为模板”创建原公共模板的新修订并清空填写值，按已有审核流程发布。旧记录固定引用旧版本；旧版实验不能覆盖当前已更新的公共模板。
- 在线填写及手写空白导出显示全量；只读结果、结果预览与默认导出走同一个服务端精简器。界面不会把精简快照保存回完整记录。

## 状态、单位与训练

原文始终保留。空白/未测、测试不适用、配方没有该物料分别记录；0、附着力等级 0 和文字结果有效。仅整格状态标记参与判断，含斜杠的测试方法仍为文字。

支持 `90`、`90g`、`90 g`；时间标准化为秒，百分比同时读取数值和 Excel 数字格式。不同黏度方法不互相换算。等级使用已约定字典，不增加等级下拉。超限、范围、混合文字保留含义，不充当精确数值。配方漏填、单位冲突、重复或待确认字段、无绑定内容会进入问题清单。

训练时按实验下各组形成样本；临时未发布字段、未测、不适用和非精确数值不自动补 0。交叉验证同时合并“同一实验”和“相同输入”的样本，避免平行组泄漏。最优方案等事后选择不作为训练特征。

## 导入、导出与打印

新增 `GET /experiments/{id}/presentation?revision=...`；沿用草稿保存接口。导出支持 `view=RESULT` 和 `view=BLANK`。

结果导出实际删除行、列，迁移合并标题、公式、冻结位置及打印范围，保留字体、边框、行高、列宽和原表打印设置。超过六组按六组设置列分页，重复 A:B 项目列，使用 100% 打印缩放。实际打印机的分页效果仍需部署环境验打。

只有一组或少量组时，共用的日期、环境温度和湿度会重排到独立行，避免这些表头信息撑出空样品列。实验编号行和配方合计行是系统定位边界；删除边界、文件结构冲突时会保留原文件并提示核对，不生成猜测的数据。

系统导出的隐藏定位页仅包含可见字段及定位信息；可见行中的空白状态用于恢复“不适用/没有物料”，不携带已移除项目的原始内容。精简表导入后展开到全量填写表。Excel 中直接插入物料行后，依据标签重新定位，不沿用失效坐标；没有元数据的客户表按分组和项目标签建立定位，歧义进入待确认。

## 验证命令与范围

先在 `jsd-aird-api` 生成测试快照，再在 `jsd-aird-web` 执行真实 Univer 测试，最后运行往返导入验收：

```powershell
cd jsd-aird-api
mvn '-Dtest=UnifiedLabSeedGeneratorTest' test
cd ../jsd-aird-web
node scripts/smoke-unified-lab.mjs
cd ../jsd-aird-api
mvn '-Dtest=UnifiedLabTemplateTest,UnifiedLabImportTest,UnifiedLabSeedGeneratorTest,ExperimentServiceTest,HistorySampleProjectionTest,SnapshotWorkbookExporterTest,PermissionRouteFilterTest,XlsxStructureParserTest,DataWorkbookServiceTest' '-Dlab.browser.snapshots=../jsd-aird-web/node_modules/.cache/unified-lab' '-Dlab.seed.output=../deployment/unified-experiments/install.sql' test
```

覆盖两份原始示例、两行新物料、八组、临时项目、撤销重做、重开、精简导出再导入、Excel 直接插行、g 用量、重复投料、百分比、等级、文字、时间、未绑定内容和不完整配方。类型检查、单元测试、ESLint、生产构建和包体积门禁分别运行；不以接口或单元测试替代真实页面验收。

字段格式回归覆盖文本、数值、整数、日期、日期时间、时间、时长和是／否，以及前导零、有效 0、false、超 24 小时时长、带单位输入、百分比单格和多格粘贴、原文中的斜杠及未测状态。2026-10-26 的格式回归中，前端 142 项通过；后端选择 121 项，118 项通过、3 项因外部浏览器夹具未生成而跳过，不计为通过。真实页面导出后按模板重新导入并生成实验草稿，八种新增字段的六组共 48 个结构化值按字段及组 ID 双向比对，差异为 0。特殊分段数字格式、1904 日期系统和桌面 Excel 打印渲染不在该轮专项验证范围。

`deployment/unified-experiments/verify_sql.py` 在独立临时 PostgreSQL 验证两组织安装、重复执行、内容冲突保护、V9 契约与 169 个映射。不会连接业务库。

全仓库的 `ArchitectureRulesTest`、`ModuleBoundaryTest` 仍失败，包含已有应用层依赖实现层、模块循环和字段注入规则违规。本次新增类未出现在最新违规条目中；不能据此声称全仓库测试通过。`XlsxStructureParserTest` 中三项依赖本地样本的既有测试会跳过，新增实验模板测试使用随代码保存的固定资源。

## 原文件 SHA-256

| 文件 | SHA-256 |
| --- | --- |
| 测试实验空白模板.xlsx | `88bfc6aaab8ef6c30e3e7c1de64c936ed3befd57525e608de6465667f09a6fc9` |
| 合成实验空白模板.xlsx | `a4207878788a672abc9dee43aaa58bacaa6a061e88af526f7b17e697c2b1121e` |
| 测试实验记录.xlsx | `5df1788833ebbee071572051b304d4f6803e95a8696350e69917bb632e85dcf5` |
| 合成实验记录.xlsx | `2ad315625e0a44554e12810c4458208291effaebe5ecdadfb5be0d984ae105dd` |
