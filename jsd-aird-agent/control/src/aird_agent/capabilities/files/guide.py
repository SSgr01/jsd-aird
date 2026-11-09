"""Trusted, on-demand authoring guide, included in the files capability fingerprint."""

FORMAT_GUIDE = """# Word、Excel、PDF 文件生成
按用户要求编写脚本，不套固定报告模板。仅在sandbox_execute中执行；不要临时联网安装依赖。
依赖：python-docx、openpyxl、reportlab、pypdf；命令libreoffice、pdftoppm、pdfinfo。
字体：aird_files.CJK_FONT_NAME = WenQuanYi Zen Hei；CJK_FONT_PATH = /usr/share/fonts/truetype/wqy/wqy-zenhei.ttc。

Word：用docx.Document创建真实.docx。标题、正文、表格按内容组织；中文样式同时设置style.font.name和style.element.rPr.rFonts中的w:eastAsia。清理默认Title样式中不需要的w:pBdr边框。长表格用w:tblHeader重复表头，并在行属性w:trPr加入w:cantSplit避免普通数据行跨页拆开；控制总列宽，保持图片比例。不要把Markdown直接写入.docx。
以下是已验证的python-docx API用法，仅示范字体与表格操作，内容仍按任务决定：
```python
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
for name in ('Normal', 'Title', 'Heading 1'):
    style = doc.styles[name]
    style.font.name = CJK_FONT_NAME
    style.element.get_or_add_rPr().get_or_add_rFonts().set(qn('w:eastAsia'), CJK_FONT_NAME)
    for border in style.element.xpath('./w:pPr/w:pBdr'):
        border.getparent().remove(border)
# headers和records由任务数据提供；先添加行，再访问其单元格。
table = doc.add_table(rows=1, cols=len(headers))
for cell, text in zip(table.rows[0].cells, headers):
    cell.text = str(text)
table.rows[0]._tr.get_or_add_trPr().append(OxmlElement('w:tblHeader'))
for values in records:
    row = table.add_row()
    for cell, value in zip(row.cells, values):
        cell.text = str(value)
for row in table.rows:
    row._tr.get_or_add_trPr().append(OxmlElement('w:cantSplit'))
```
使用qn('w:eastAsia')设置正确的Word命名空间，边框用上述XPath访问。表格rows参数仅创建指定数量的行，不能索引尚未创建的行。
Excel：用openpyxl.Workbook创建真实.xlsx。数字写成数值类型，明确单位和精度；按需求设置多工作表、列宽、筛选、冻结行和样式。公式用=表达式；openpyxl不计算公式。需要可读取的结果时，先将原始工作簿写入scratch/，再用convert_office(source, 'output/', target='xlsx')重算；用data_only=True重新打开核对缓存结果及错误。LibreOffice不保证兼容所有Excel公式，不能把缺失缓存或错误当作已完成。
PDF：ReportLab的pdfmetrics.registerFont(TTFont('CJK', CJK_FONT_PATH, subfontIndex=0))注册中文字体；正文用ParagraphStyle(fontName='CJK',wordWrap='CJK')与Platypus的Paragraph、Table、SimpleDocTemplate排版。表格长文本使用Paragraph，控制列宽并repeatRows=1；段落与跨页表格须检查。单位符号μ、°、±、上下标和换行均需核对。
若要求Word和PDF内容与版式对应，先生成DOCX，再convert_office(source, 'output/pdf/', target='pdf')；XLSX也可转PDF，需设置打印区域、纸张方向、重复表头和缩放。不要将多个工作表挤成无法阅读的一页。

可复用帮助函数：from aird_files import check_file, convert_office, CJK_FONT_NAME, CJK_FONT_PATH。
check_file(path)重新打开文件并返回基本结构；XLSX结果含formulas、formulas_without_cached_values、formula_errors。convert_office使用独立临时用户配置、超时和新输出目录，不覆盖原件；转换后仍需检查内容。
沙箱导出前会重新解析DOCX/XLSX/PDF，损坏文件不会交付。解析成功不代表排版与内容正确；需要时用pdftoppm渲染PDF，检查中文、分页、图片和表格是否截断。可将审阅用图片放scratch/，仅登记用户要求的最终文件。
生成后检查原始数据、单位、公式和实际要求的段落/表格/工作表是否完整。源数据不足或转换失败就说明具体缺口，不提供空文件或伪装格式，不声称已经人工审核。
"""
