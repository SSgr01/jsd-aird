import json
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

NS = {
    "w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
}


def verify(path: Path) -> dict:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        document = ET.fromstring(archive.read("word/document.xml"))
        body = document.find("w:body", NS)
        paragraphs = body.findall("w:p", NS) if body is not None else []
        tables = body.findall("w:tbl", NS) if body is not None else []
        page_breaks = document.findall(".//w:br[@w:type='page']", NS)
        headers = sorted(name for name in names if name.startswith("word/header") and name.endswith(".xml"))
        footers = sorted(name for name in names if name.startswith("word/footer") and name.endswith(".xml"))
        media = sorted(name for name in names if name.startswith("word/media/"))
        heading_count = 0
        list_count = 0
        text_samples = []
        for paragraph in paragraphs:
            style = paragraph.find("w:pPr/w:pStyle", NS)
            if style is not None and (style.get(f"{{{NS['w']}}}val") or "").lower().startswith("heading"):
                heading_count += 1
            if paragraph.find("w:pPr/w:numPr", NS) is not None:
                list_count += 1
            text = "".join(node.text or "" for node in paragraph.findall(".//w:t", NS)).strip()
            if text and len(text_samples) < 8:
                text_samples.append(text[:120])
        table_shapes = []
        for table in tables:
            rows = table.findall("w:tr", NS)
            columns = 0
            for row in rows:
                columns = max(columns, len(row.findall("w:tc", NS)))
            table_shapes.append({"rows": len(rows), "columns": columns})
        return {
            "file": path.name,
            "paragraphCount": len(paragraphs),
            "headingCount": heading_count,
            "listParagraphCount": list_count,
            "tableCount": len(tables),
            "tableShapes": table_shapes,
            "pageBreakCount": len(page_breaks),
            "headerParts": headers,
            "footerParts": footers,
            "mediaParts": media,
            "textSamples": text_samples,
        }


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: verify-docx-fixtures.py <fixture-dir> <output-json>", file=sys.stderr)
        return 2
    fixture_dir = Path(sys.argv[1])
    output = Path(sys.argv[2])
    results = [verify(path) for path in sorted(fixture_dir.glob("*.docx"))]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(results, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
