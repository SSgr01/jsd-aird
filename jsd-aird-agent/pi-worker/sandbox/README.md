# 文件生成与隔离执行环境

镜像预装 NumPy、Pandas、SciPy、python-docx、openpyxl、ReportLab、pypdf，以及 LibreOffice Writer/Calc、Poppler 和中文字体。运行时保持无网络、非 root、只读根文件系统和原有资源配额，不能临时安装依赖。

```powershell
docker build -t jsd-aird-analysis:1 jsd-aird-agent/pi-worker/sandbox
```

Agent 通过现有 `files` 能力和 `skill_read(name="files")` 获取按需格式指南，根据任务编写脚本。这里不提供固定报告模板，也不新增格式专用工具。`aird_files` 帮助函数仅在容器内运行：

- `check_file(path)`：重新打开 DOCX、XLSX、PDF，检查结构；Excel 同时报告公式缓存缺失及错误。
- `convert_office(source, output_dir, target="pdf")`：Word/Excel 转 PDF。
- `convert_office(source, output_dir, target="xlsx")`：Excel 重算并另存；缺少结果缓存或存在公式错误时拒绝当作成功重算。

转换使用独立临时 LibreOffice 配置、超时和新的输出目录，避免并发串扰和覆盖源文件。复杂 Excel 公式、外部数据源和其他 Office 特性仍需按具体任务验证；不能仅凭转换命令成功承诺完全兼容。

中文字体使用 Debian `fonts-wqy-zenhei` 包，字体路径和名称由 `aird_files.CJK_FONT_PATH`、`CJK_FONT_NAME` 提供。字体许可随包保留在镜像的 `/usr/share/doc/fonts-wqy-zenhei/` 中，不依赖宿主机字体。生成的 DOCX/XLSX 在客户设备上的最终字体替换仍由其 Office 环境决定；需要稳定版式时同时提供嵌入字体的 PDF。

Runner 在导出前重新解析 DOCX/XLSX/PDF，损坏文件不交付。解析不替代版式检查或专业内容核对。Word、PDF 中的中文、表格、图片、分页，以及 Excel 中的公式和缓存结果通过真实容器回归验收：

```powershell
$env:HARNESS_DOCUMENT_IMAGE = 'jsd-aird-analysis:1'
cd jsd-aird-agent/control
.\.venv\Scripts\python.exe -m pytest harness_tests/test_document_formats.py -q -p no:cacheprovider --basetemp ../../.runtime/document-format-tests
```

测试使用合成数据，包含跨页表格、中文和单位、图片、多工作表、公式重算、PDF 转换及损坏文件拒绝。生成物和预览图片仅保存在忽略的运行目录中，不提交。
