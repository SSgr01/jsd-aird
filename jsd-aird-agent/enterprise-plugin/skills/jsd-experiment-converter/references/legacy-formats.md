# 旧格式：只在遇到 .xls / .doc 时读取

服务器文件夹模式由 `folder_conversion(action=source)` 在后台完成严格规范化并提供 XLSX/DOCX 工作副本；源与副本指纹分别保留，详见 [server-folder.md](server-folder.md)。此模式不要执行下文的本地 LibreOffice Kit 流程，也不在沙箱重新转换。

以下转换器说明适用于本地模式；其他系统模式只在实际工具明确支持时使用对应适配。不要把全部旧格式的预转换设为所有 xlsx 的前置关卡。

- `.xls`：若当前解释器有 xlrd，可直接读取原始单元格用于核对；openpyxl 不能读 xls。没有 xlrd 时使用当前 Harness 提供的本地转换器，把副本写进 `.dsh/cache/jsd-experiment-converter/legacy/`，不改原件。
- `.doc`：先转 docx，再用 `workflow.extract_docx(path)` 读取 XML。它遍历所有嵌套表，避免只读顶层表而漏掉配方。不得仅改后缀。
- 原件、转换结果及验证记录按原件 SHA256 缓存；新会话命中缓存且副本指纹正确时复用，不再次转换。

## DSH LibreOffice Kit

从当前环境定位模块路径一次，不照抄其他电脑的安装路径。以实际模块的 API 为准。已有环境使用过的形式：

```js
const { createConverter } = await import(currentKitModuleUrl);
const conv = await createConverter({ timeoutMs: 120000 });
try {
  await conv.convert({ inputPath, outputPath });
} finally {
  await conv.dispose();
}
```

先 `await createConverter`，实例按批次复用。不并发启动多个 Office 实例，不默认给一次调用15分钟。报错修正后仅重试一次，然后记录该文件阻塞，继续其他输入。

## 验证的证据强弱

xls 优先对比原件（xlrd）与副本（openpyxl）的表名、坐标、原值、组数、物料和公式结果。CSV 是显示值，比较需归一化百分数（12.56% 对应 0.1256）、日期和数字格式；差异需解释。

同一个 LibreOffice 引擎分别导出 xlsx 和 CSV **不是独立验证**：二者一致只能排除部分导出差异，不能证明共同的导入阶段没有丢数据。没有独立读取器时，明确标注验证受限；不要为了“独立核对”再对每张表无条件重复转换。

docx 检查全部表（含嵌套）、段落、组编号及配方结构；可与原件另一路可用文本提取交叉核对。UV高硬度样本应识别2张表（外层+嵌套）、6组、8种材料。XZ文档没有已验证映射，不能套用UV样本。

`execute_plan.py` 已支持 `.docx` 原件（含嵌套表），按原件 SHA256 绑定计划，`source_sheet` 用 `table-N`，地址按真实表格网格推导（跨列/纵向合并会改变列号），详见 execution.md。本地 `.xls`/`.doc` 仍需先转换副本并核对，未完成适配前保持 blocked；服务器文件夹任务使用后台已经严格核验的副本。任何模式都不能把未执行或失败说成完成。
