"""Optional sandbox helpers. No fixed document template or host execution path."""

import subprocess
import tempfile
from pathlib import Path

CJK_FONT_NAME = "WenQuanYi Zen Hei"
CJK_FONT_PATH = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"


def check_file(path):
    """Reopen real formats; report structure without claiming scientific verification."""
    path = Path(path)
    if not path.is_file() or path.stat().st_size == 0:
        raise ValueError("Generated file is missing or empty")
    suffix = path.suffix.lower()
    if suffix == ".docx":
        from docx import Document

        doc = Document(path)
        return {"format": "docx", "paragraphs": len(doc.paragraphs), "tables": len(doc.tables)}
    if suffix == ".xlsx":
        from openpyxl import load_workbook

        formulas = load_workbook(path, read_only=True, data_only=False)
        values = load_workbook(path, read_only=True, data_only=True)
        try:
            count, missing, errors = 0, 0, 0
            for sheet in formulas:
                for row, cached in zip(sheet.iter_rows(), values[sheet.title].iter_rows(), strict=True):
                    for cell, value in zip(row, cached, strict=True):
                        if cell.data_type == "f":
                            count += 1
                            missing += value.value is None
                            errors += value.data_type == "e"
            return {
                "format": "xlsx", "sheets": formulas.sheetnames, "formulas": count,
                "formulas_without_cached_values": missing, "formula_errors": errors,
            }
        finally:
            formulas.close()
            values.close()
    if suffix == ".pdf":
        from pypdf import PdfReader

        reader = PdfReader(path, strict=True)
        if reader.is_encrypted or not reader.pages:
            raise ValueError("Generated PDF must be readable and have at least one page")
        # Read every page's content stream; text is a diagnostic, not layout verification.
        characters = sum(len(page.extract_text() or "") for page in reader.pages)
        return {"format": "pdf", "pages": len(reader.pages), "text_characters": characters}
    raise ValueError("Use check_file for docx, xlsx or pdf")


def convert_office(source, output_dir, *, target="pdf", timeout=60):
    """Convert DOCX/XLSX to PDF or recalculate XLSX in an isolated LibreOffice profile."""
    source, output_dir = Path(source).resolve(), Path(output_dir).resolve()
    if source.suffix.lower() not in {".docx", ".xlsx"} or target not in {"pdf", "xlsx"}:
        raise ValueError("Supported conversions: DOCX/XLSX to PDF, XLSX to recalculated XLSX")
    if target == "xlsx" and source.suffix.lower() != ".xlsx":
        raise ValueError("Only an XLSX workbook can be recalculated")
    check_file(source)
    output_dir.mkdir(parents=True, exist_ok=True)
    destination = output_dir / (source.stem + "." + target)
    if destination == source or destination.exists():
        raise ValueError("Choose a new output directory; conversion never overwrites an existing file")
    export_filter = "xlsx:Calc MS Excel 2007 XML" if target == "xlsx" else "pdf"
    with tempfile.TemporaryDirectory(prefix="aird-office-") as profile:
        result = subprocess.run(
            [
                "libreoffice", "-env:UserInstallation=" + Path(profile).as_uri(),
                "--headless", "--norestore", "--nodefault", "--nofirststartwizard",
                "--convert-to", export_filter, "--outdir", str(output_dir), str(source),
            ],
            capture_output=True, text=True, timeout=timeout, check=True,
        )
    if not destination.is_file():
        raise RuntimeError("Office conversion produced no file: " + result.stderr[-1000:])
    checked = check_file(destination)
    if target == "xlsx" and (checked["formulas_without_cached_values"] or checked["formula_errors"]):
        raise ValueError("Formula recalculation left missing values or errors; do not deliver as calculated results")
    return destination
