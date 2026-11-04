"""Structural format checks. These neither require citations nor alter file bytes."""

import csv
import io
import json
import zipfile
from dataclasses import dataclass
from pathlib import PurePosixPath

from fastapi import HTTPException


def reject_json_constant(value):
    raise ValueError(f"Non-finite JSON number: {value}")


@dataclass(frozen=True)
class DelimitedTable:
    text: str
    delimiter: str
    keys: tuple[str, ...]

    def rows(self):
        reader = csv.reader(io.StringIO(self.text, newline=""), delimiter=self.delimiter, strict=True)
        next(reader)
        for row_number, row in enumerate(reader, 2):
            if row:
                yield row_number, dict(zip(self.keys, row, strict=True))


def validate_format(path, content):
    suffix = PurePosixPath(path).suffix.lower()
    if suffix in {".docx", ".xlsx"}:
        required = "word/document.xml" if suffix == ".docx" else "xl/workbook.xml"
        try:
            with zipfile.ZipFile(io.BytesIO(content)) as archive:
                if not {"[Content_Types].xml", required} <= set(archive.namelist()):
                    raise ValueError("Missing OOXML document parts")
                if sum(info.file_size for info in archive.infolist()) > 100 * 1024 * 1024:
                    raise ValueError("Expanded document exceeds limits")
        except (zipfile.BadZipFile, ValueError, OSError) as error:
            raise HTTPException(422, "EXPORT_FORMAT_INVALID: generate a real, bounded Office document") from error
        return None
    if suffix == ".pdf":
        if not content.startswith(b"%PDF-") or b"%%EOF" not in content[-1024:]:
            raise HTTPException(422, "EXPORT_FORMAT_INVALID: generate a complete PDF document")
        return None
    if suffix not in {".csv", ".tsv", ".json"}:
        return None
    try:
        text = content.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise HTTPException(422, "EXPORT_ENCODING_INVALID: use UTF-8") from error
    try:
        if suffix == ".json":
            return json.loads(text, parse_constant=reject_json_constant)
        reader = csv.reader(
            io.StringIO(text, newline=""), delimiter="\t" if suffix == ".tsv" else ",", strict=True
        )
        header = next(reader, [])
        keys = [cell.strip().casefold() for cell in header]
        if not keys or any(not key for key in keys) or len(set(keys)) != len(keys):
            raise HTTPException(422, "EXPORT_HEADER_INVALID: write one nonempty, unique header row")
        count = 0
        for row_number, row in enumerate(reader, 2):
            if not row:
                continue
            if [cell.strip().casefold() for cell in row] == keys:
                raise HTTPException(422, f"EXPORT_HEADER_REPEATED: remove the repeated header at row {row_number}")
            if len(row) != len(header):
                raise HTTPException(422, f"EXPORT_ROW_INVALID: row {row_number} has a different column count")
            count += 1
        if not count:
            raise HTTPException(422, "EXPORT_ROWS_MISSING: the exported table contains no data")
        return DelimitedTable(text, "\t" if suffix == ".tsv" else ",", tuple(keys))
    except (csv.Error, ValueError, RecursionError) as error:
        raise HTTPException(422, "EXPORT_FORMAT_INVALID: generate a valid, bounded JSON or delimited table") from error
