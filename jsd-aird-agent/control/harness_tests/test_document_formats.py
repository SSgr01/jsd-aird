"""Real generation under the production Docker restrictions; explicitly opt in with an image."""

import asyncio
import json
import os
import sys
from uuid import uuid4

import pytest
from fastapi import HTTPException

from aird_agent.core.workspace import Workspace
from aird_agent.sandbox.broker import BrokerSettings, ContainerRunner, Execution

SCRIPT = r'''
import json
import subprocess
from pathlib import Path
from docx import Document
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor
from openpyxl import Workbook, load_workbook
from openpyxl.styles import Font, PatternFill
from PIL import Image, ImageDraw
from pypdf import PdfReader
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image as PdfImage
from aird_files import CJK_FONT_NAME, CJK_FONT_PATH, check_file, convert_office

out = Path('output').resolve()
scratch = Path('scratch').resolve()
image = Image.new('RGB', (480, 160), 'white')
draw = ImageDraw.Draw(image)
draw.rectangle((30, 40, 190, 130), fill='#4b83c4')
draw.rectangle((230, 70, 390, 130), fill='#82b798')
image.save(scratch / 'chart.png')

doc = Document()
section = doc.sections[0]
section.left_margin = section.right_margin = Cm(2)
for name in ['Normal', 'Title', 'Heading 1']:
    style = doc.styles[name]
    style.font.name = CJK_FONT_NAME
    style.element.rPr.rFonts.set(qn('w:eastAsia'), CJK_FONT_NAME)
    style.font.color.rgb = RGBColor(0, 0, 0)
    for border in style.element.xpath('./w:pPr/w:pBdr'):
        border.getparent().remove(border)
doc.styles['Normal'].font.size = Pt(11)
doc.add_heading('文件能力验收', 0)
doc.add_paragraph('这是自动回归用的合成数据，不是文献结论。水质量计算为 1 kg × 81% = 0.81 kg。')
doc.add_picture(str(scratch / 'chart.png'), width=Cm(12))
doc.add_heading('中文段落与跨页表格', level=1)
doc.add_paragraph('中文长段落需要自然换行，保留数值、单位和条件。' * 20)
table = doc.add_table(rows=1, cols=3)
table.style = 'Table Grid'
for cell, text in zip(table.rows[0].cells, ['样品', '结果', '条件与说明']):
    cell.text = text
repeat = OxmlElement('w:tblHeader')
table.rows[0]._tr.get_or_add_trPr().append(repeat)
for i in range(38):
    cells = table.add_row().cells
    for cell, text in zip(cells, [f'样品 {i+1}', '0.81 kg', '25 °C；粒径 10 μm；允许偏差 ±0.02。长文本应自然换行。']):
        cell.text = text
for row in table.rows:
    row._tr.get_or_add_trPr().append(OxmlElement('w:cantSplit'))
doc.save(out / 'acceptance.docx')
assert check_file(out / 'acceptance.docx')['tables'] == 1
word_pdf = convert_office(out / 'acceptance.docx', out / 'word-pdf')
assert len(PdfReader(word_pdf).pages) >= 2

wb = Workbook()
data = wb.active
data.title = '输入数据'
data.append(['项目', '数值', '单位'])
data.append(['总质量', 1, 'kg'])
data.append(['含水比例', 0.81, '比例'])
calc = wb.create_sheet('计算结果')
calc.append(['项目', '数值', '单位'])
calc.append(['水质量', "='输入数据'!B2*'输入数据'!B3", 'kg'])
notes = wb.create_sheet('说明')
notes.append(['说明', '这些是合成测试数据，不代表科学验证。'])
for sheet in wb:
    sheet.freeze_panes = 'A2'
    sheet.auto_filter.ref = sheet.dimensions
    for cell in sheet[1]:
        cell.font = Font(name=CJK_FONT_NAME, bold=True, color='FFFFFF')
        cell.fill = PatternFill('solid', fgColor='32649A')
    for column in ['A', 'B', 'C']:
        sheet.column_dimensions[column].width = 28
    sheet.sheet_properties.pageSetUpPr.fitToPage = True
    sheet.page_setup.orientation = 'landscape'
    sheet.page_setup.fitToWidth = 1
    sheet.page_setup.fitToHeight = 0
    sheet.print_title_rows = '1:1'
wb.save(scratch / 'acceptance.xlsx')
assert check_file(scratch / 'acceptance.xlsx')['formulas_without_cached_values'] == 1
excel = convert_office(scratch / 'acceptance.xlsx', out, target='xlsx')
cached = load_workbook(excel, data_only=True)
assert abs(cached['计算结果']['B2'].value - 0.81) < 1e-12
cached.close()
assert check_file(excel)['formula_errors'] == 0
excel_pdf = convert_office(excel, out / 'excel-pdf')
bad = Workbook()
bad.active['A1'] = '=1/0'
bad.save(scratch / 'formula-error.xlsx')
try:
    convert_office(scratch / 'formula-error.xlsx', scratch / 'recalculated-error', target='xlsx')
except ValueError:
    pass
else:
    raise AssertionError('A formula error must not be delivered as a successful calculation')

pdfmetrics.registerFont(TTFont('CJK', CJK_FONT_PATH, subfontIndex=0))
style = ParagraphStyle('CJK', fontName='CJK', fontSize=11, leading=18, wordWrap='CJK')
story = [Paragraph('文件能力验收', style), Spacer(1, 12),
         Paragraph('合成数据计算为 0.81 kg。单位与符号：25 °C、10 μm、±0.02、g/cm³。', style),
         PdfImage(str(scratch / 'chart.png'), width=360, height=120),
         Paragraph('中文长段落与表格分页检查。' * 35, style), Spacer(1, 12)]
rows = [[Paragraph(x, style) for x in ['样品', '数值', '条件与说明']]]
for i in range(50):
    rows.append([Paragraph(x, style) for x in [f'样品 {i+1}', '0.81 kg', '25 °C；长文本自然换行，完整保留条件与单位。']])
grid = Table(rows, colWidths=[80, 90, 300], repeatRows=1)
grid.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),colors.HexColor('#edf1f5')),('VALIGN',(0,0),(-1,-1),'TOP'),('GRID',(0,0),(-1,-1),0.3,colors.grey)]))
story.append(grid)
SimpleDocTemplate(str(out / 'direct.pdf'), pagesize=A4, leftMargin=42, rightMargin=42).build(story)
assert check_file(out / 'direct.pdf')['pages'] >= 2
for pdf in [out / 'direct.pdf', word_pdf, excel_pdf]:
    text = ''.join(page.extract_text() or '' for page in PdfReader(pdf).pages)
    assert '0.81' in text
    assert ('文件能力验收' in text) if pdf == out / 'direct.pdf' else True
    previews = out / ('preview-' + pdf.parent.name + '-' + pdf.stem)
    subprocess.run(['pdftoppm', '-scale-to', '1100', '-png', str(pdf), str(previews)], check=True, capture_output=True)
checks = {p.relative_to(out).as_posix(): check_file(p) for p in [out / 'acceptance.docx', excel, out / 'direct.pdf', word_pdf, excel_pdf]}
(out / 'format-checks.json').write_text(json.dumps(checks, ensure_ascii=False), encoding='utf8')
print(json.dumps(checks, ensure_ascii=False))
'''


@pytest.fixture
def document_runner(tmp_path):
    image = os.environ.get("HARNESS_DOCUMENT_IMAGE")
    if not image:
        pytest.skip("Set HARNESS_DOCUMENT_IMAGE to a built sandbox image for real format acceptance")
    runner = ContainerRunner(BrokerSettings(
        token="synthetic-test-token-" + "x" * 32, workspace_root=tmp_path, image=image, _env_file=None,
    ))
    workspace = Workspace(tmp_path / "workspaces", str(uuid4()), str(uuid4()))
    workspace.prepare()
    return runner, workspace


async def test_real_chinese_documents_conversion_and_formula_values(document_runner):
    runner, workspace = document_runner
    request = Execution(organization_id=uuid4(), run_id=uuid4(), execution_id=uuid4().hex, language="python", script=SCRIPT)
    result = await execute(runner, workspace, request)
    assert result["exit_code"] == 0, result["output"]
    checks = json.loads(workspace.resolve("output/format-checks.json").read_text(encoding="utf8"))
    assert checks["acceptance.xlsx"]["sheets"] == ["输入数据", "计算结果", "说明"]
    assert checks["acceptance.xlsx"]["formulas_without_cached_values"] == 0
    assert checks["direct.pdf"]["pages"] >= 2
    assert len([item for item in result["artifacts"] if item["path"].endswith(".png")]) >= 3


async def test_corrupt_office_output_is_not_exported(document_runner):
    runner, workspace = document_runner
    request = Execution(
        organization_id=uuid4(), run_id=uuid4(), execution_id=uuid4().hex, language="python",
        script="from pathlib import Path; Path('output/fake.docx').write_text('plain text')",
    )
    with pytest.raises(HTTPException, match="SANDBOX_CONTAINER_FAILED"):
        await execute(runner, workspace, request)
    assert not workspace.resolve("output/fake.docx", writable=True).exists()


async def execute(runner, workspace, request):
    if sys.platform != "win32":
        return await runner.execute(workspace, request)
    # PostgreSQL fixtures use Selector on Windows; real subprocesses require Proactor.
    def subprocess_loop():
        with asyncio.Runner(loop_factory=asyncio.ProactorEventLoop) as loop:
            return loop.run(runner.execute(workspace, request))

    return await asyncio.to_thread(subprocess_loop)
