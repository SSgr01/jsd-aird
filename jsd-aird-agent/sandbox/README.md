# 文件生成与隔离执行环境

镜像预装 Node 24.11.1、官方 `@deepseek-ai/libreoffice-kit@0.1.5`、NumPy、Pandas、SciPy、python-docx、openpyxl、ReportLab、pypdf，以及 LibreOffice Writer/Calc、Poppler 和中文字体。运行时保持无网络、非 root、只读根文件系统和原有资源配额，不能临时安装依赖。

```powershell
docker build -t jsd-aird-analysis:1 jsd-aird-agent/sandbox
```

构建网络无法访问 PyPI 时，可通过构建参数选择可达的 HTTPS 软件源，版本约束不变：

```powershell
docker build --build-arg SANDBOX_PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple -t jsd-aird-analysis:1 jsd-aird-agent/sandbox
```

此参数只用于构建，不改变容器运行时的网络边界。

Agent 通过 dsh 原生 `skill` 按需读取固定上游的 Office 技能，并使用原生文件、bash、预览和 present。企业适配只映射沙箱中的官方运行路径：

```text
/usr/local/bin/node /opt/dsh-office/node_modules/@deepseek-ai/libreoffice-kit/lib/cli.js
```

原生技能资源以 `/opt/dsh-skills/office/` 只读虚拟路径提供，由文件桥读取固定上游资源，不允许脚本改写。原版技能正文不另写一份。官方 Kit 支持导出 PDF、Excel 重算和页面渲染；本地 Linux 验证使用 wasm 后端。保留的 Python `aird_files` 帮助函数只在容器内使用：

- `check_file(path)`：重新打开 DOCX、XLSX、PDF，检查结构；Excel 同时报告公式缓存缺失及错误。
- `convert_office(source, output_dir, target="pdf")`：Word/Excel 转 PDF。
- `convert_office(source, output_dir, target="xlsx")`：Excel 重算并另存；缺少结果缓存或存在公式错误时拒绝当作成功重算。

Python 转换使用独立临时 LibreOffice 配置、超时和新的输出目录。复杂 Excel 公式、外部数据源和其他 Office 特性仍需按具体任务验证；不能仅凭转换命令成功承诺完全兼容。

中文字体使用 Debian `fonts-wqy-zenhei` 包，字体路径和名称由 `aird_files.CJK_FONT_PATH`、`CJK_FONT_NAME` 提供。字体许可随包保留在镜像的 `/usr/share/doc/fonts-wqy-zenhei/` 中，不依赖宿主机字体。生成的 DOCX/XLSX 在客户设备上的最终字体替换仍由其 Office 环境决定；需要稳定版式时按用户要求提供嵌入字体的 PDF。

独立 Broker 可通过 `scripts/dev-sandbox.ps1 -Image <镜像>` 选择已构建镜像。2026-11-06 已从本 Dockerfile 构建 `jsd-aird-analysis:clean-20261106`；本机 PyPI TLS 连接失败后，通过上述 HTTPS 软件源完成构建，首次失败日志保留。新镜像在无网络、非 root、只读根及原资源配额下通过 Node、官方 Office Kit 导入、Python 固定依赖、LibreOffice、Poppler 和中文字体检查。三格式文件客户验收沿用已完成的结果，本次没有重新生成或验收客户文件。当前验收服务仍使用既有 `native-office` 镜像，未自动切换 Broker。

Runner 在导出前重新解析 DOCX/XLSX/PDF，损坏文件不交付。解析不替代版式检查或专业内容核对。Word、PDF 中的中文、表格、图片、分页，以及 Excel 中的公式和缓存结果通过真实容器回归验收：

```powershell
$env:HARNESS_DOCUMENT_IMAGE = 'jsd-aird-analysis:1'
cd jsd-aird-agent/control
.\.venv\Scripts\python.exe -m pytest harness_tests/test_document_formats.py -q -p no:cacheprovider --basetemp ../../.runtime/document-format-tests
```

测试使用合成数据，包含跨页表格、中文和单位、图片、多工作表、公式重算、PDF 转换及损坏文件拒绝。生成物和预览图片仅保存在忽略的运行目录中，不提交。
