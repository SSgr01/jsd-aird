"""Read-only evidence for local historical extraction; no semantic inference."""
from __future__ import annotations

import posixpath
import re
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

from workflow import digest, scalar


def contained(root, relative):
    root = Path(root).resolve()
    path = (root / relative).resolve()
    if not path.is_relative_to(root) or path == root:
        raise ValueError('路径越出当前工作区：' + str(relative))
    # A junction/symlink must not introduce a second source location.
    current = root
    for part in Path(relative).parts:
        current = current / part
        if current.is_symlink():
            raise ValueError('工作区来源不能是符号链接')
    return path


def nonempty(value):
    return value is not None and (not isinstance(value, str) or bool(value.strip()))


class HistorySource:
    def __init__(self, root, source, expected_hash=None):
        self.root = Path(root).resolve()
        self.path = contained(self.root, source)
        if not self.path.is_file() or self.path.name.startswith('~$'):
            raise ValueError('来源不存在或为Office锁文件')
        if self.path.suffix.lower() not in {'.xlsx', '.docx'}:
            raise ValueError('历史提取读取XLSX/DOCX工作副本；XLS/DOC须先经项目Java严格规范化')
        if self.path.stat().st_size > 64 * 1024 * 1024:
            raise ValueError('本地历史提取单文件超过64MiB；不截断读取')
        self.sha = digest(self.path)
        if expected_hash is not None and self.sha != expected_hash:
            raise ValueError('工作副本指纹已变，需重新检查映射')
        self.cells, self.aliases, self.images, self.used = {}, {}, {}, set()
        self.used_images, self.books = set(), []
        try:
            with zipfile.ZipFile(self.path) as z:
                if sum(x.file_size for x in z.infolist()) > 256 * 1024 * 1024:
                    raise ValueError('Office展开内容超过256MiB；不截断读取')
                for item in z.infolist():
                    if item.file_size > 128 * 1024 * 1024:
                        raise ValueError('Office单个部件超过128MiB')
                    if item.filename.startswith(('word/media/', 'xl/media/')) and not item.is_dir():
                        if (posixpath.normpath(item.filename) != item.filename or '\\' in item.filename
                                or ':' in item.filename):
                            raise ValueError('Office图片路径异常')
                        import hashlib
                        self.images[item.filename] = {'part': item.filename,
                            'sha256': hashlib.sha256(z.read(item)).hexdigest(), 'bytes': item.file_size}
            if self.path.suffix.lower() == '.docx':
                self._word()
            else:
                self._excel()
            if len(self.cells) > 200000:
                raise ValueError('来源超过200000个非空单元格；请显式拆分处理，不截断')
        except Exception:
            self.close()
            raise

    def _excel(self):
        from openpyxl import load_workbook
        formula = load_workbook(self.path, data_only=False)
        self.books.append(formula)
        cached = load_workbook(self.path, data_only=True)
        self.books.append(cached)
        for sheet in formula:
            # Iterate stored physical cells, not a potentially enormous empty
            # rectangle produced by formatting a far-away row or column.
            for cell in sheet._cells.values():
                if not nonempty(cell.value):
                    continue
                value = cached[sheet.title][cell.coordinate].value if cell.data_type == 'f' else cell.value
                self.cells[sheet.title + '!' + cell.coordinate] = {
                    'rawValue': scalar(value), 'numberFormat': cell.number_format,
                    'physicalValueType': cell.data_type,
                    'formula': cell.value if cell.data_type == 'f' else None,
                    'formulaCacheMissing': cell.data_type == 'f' and value is None}
                if len(self.cells) > 200000:
                    raise ValueError('来源超过200000个非空单元格；不截断读取')
            for area in sheet.merged_cells.ranges:
                if len(self.aliases) + (area.max_row-area.min_row+1)*(area.max_col-area.min_col+1) > 200000:
                    raise ValueError('来源合并网格展开超过200000格；不截断读取')
                anchor = sheet.cell(area.min_row, area.min_col).coordinate
                from openpyxl.utils import get_column_letter
                for row in range(area.min_row, area.max_row + 1):
                    for col in range(area.min_col, area.max_col + 1):
                        ref = sheet.title + '!' + get_column_letter(col) + str(row)
                        self.aliases[ref] = sheet.title + '!' + anchor

    def _word(self):
        from execute_plan import _docx_table_views
        from openpyxl.utils import get_column_letter
        views, paragraphs = _docx_table_views(self.path)
        for name, sheet in views.items():
            for row in sheet:
                for cell in row:
                    if nonempty(cell.value):
                        self.cells[name + '!' + cell.coordinate] = {
                            'rawValue': cell.value, 'physicalValueType': 's', 'numberFormat': '',
                            'formula': None, 'formulaCacheMissing': False}
        # Grid-span aliases represent one physical source, not independent data.
        ns = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
        with zipfile.ZipFile(self.path) as z:
            document = ET.fromstring(z.read('word/document.xml'))
        for index, table in enumerate(document.findall('.//w:tbl', ns), 1):
            for row_number, row in enumerate(table.findall('w:tr', ns), 1):
                column = 1
                for cell in row.findall('w:tc', ns):
                    span = cell.find('w:tcPr/w:gridSpan', ns)
                    width = 1 if cell.findall('.//w:tbl', ns) else int(span.get('{'+ns['w']+'}val')) if span is not None else 1
                    anchor = f'table-{index}!{get_column_letter(column)}{row_number}'
                    for offset in range(1, width):
                        ref = f'table-{index}!{get_column_letter(column+offset)}{row_number}'
                        self.aliases[ref] = anchor
                        self.cells.pop(ref, None)
                    column += width
        for index, text in enumerate(paragraphs, 1):
            if nonempty(text):
                self.cells[f'paragraph!{index}'] = {'rawValue': text, 'physicalValueType': 's',
                    'numberFormat': '', 'formula': None, 'formulaCacheMissing': False}

    def has(self, ref):
        return self.aliases.get(ref, ref) in self.cells

    def read(self, ref):
        if isinstance(ref, str):
            canonical = self.aliases.get(ref, ref)
            # An absent coordinate must not be used as an invented empty amount.
            if canonical not in self.cells:
                raise ValueError('引用必须指向真实非空来源，缺项请省略而非伪造空白坐标：' + ref)
            self.used.add(canonical)
            item = self.cells[canonical]
            evidence = {'sourceType': 'LOCAL_HISTORY', 'sourceFileHash': self.sha,
                'sourcePath': self.path.relative_to(self.root).as_posix(), 'anchor': canonical,
                **item, 'evidenceType': 'SOURCE_CELL'}
            return item['rawValue'], evidence
        if not isinstance(ref, dict) or set(ref) != {'image', 'sha256', 'bbox', 'text'}:
            raise ValueError('图片来源须明确image、sha256、bbox和text')
        image = self.images.get(ref['image'])
        if image is None or image['sha256'] != ref['sha256']:
            raise ValueError('图片部件或指纹不匹配')
        box = ref['bbox']
        if (not isinstance(box, list) or len(box) != 4 or any(isinstance(v, bool) or not isinstance(v, (int, float)) for v in box)
                or not 0 <= box[0] < box[2] <= 1 or not 0 <= box[1] < box[3] <= 1):
            raise ValueError('图片bbox须为0到1之间的有效[x1,y1,x2,y2]')
        if not isinstance(ref['text'], str) or not ref['text'].strip():
            raise ValueError('图片转录须保留非空原文')
        self.used_images.add(ref['image'])
        return ref['text'], {'sourceType': 'LOCAL_HISTORY', 'sourceFileHash': self.sha,
            'sourcePath': self.path.relative_to(self.root).as_posix(), 'imagePart': image['part'],
            'imageSha256': image['sha256'], 'bbox': box, 'rawValue': ref['text'],
            'evidenceType': 'IMAGE_TRANSCRIPTION', 'requiresSourceReview': True}

    def overview(self):
        counts = {}
        for ref in self.cells:
            name = ref.rsplit('!', 1)[0]
            counts[name] = counts.get(name, 0) + 1
        return {'source': self.path.relative_to(self.root).as_posix(), 'source_sha256': self.sha,
                'scopes': counts, 'images': list(self.images.values()), 'nonempty': len(self.cells)}

    def close(self):
        for book in self.books:
            book.close()
        self.books.clear()
