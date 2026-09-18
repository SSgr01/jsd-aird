"""Create portable customer-template workbooks for the isolated demo.

This script only creates demo input files. It never writes the configured
database and never changes the customer source workbooks.
"""
from __future__ import annotations

import argparse
from pathlib import Path
from random import Random

from openpyxl import load_workbook
from openpyxl.cell.cell import MergedCell


def set_value(sheet, row: int, col: int, value) -> None:
    cell = sheet.cell(row=row, column=col)
    if not isinstance(cell, MergedCell):
        cell.value = value


def fill_sheet(sheet, start: int, rng: Random, owner: str) -> None:
    for offset, col in enumerate(range(4, 10)):
        index = start + offset
        total = 98.8 if index > 300 else 100.0
        resin = round(58 + (index % 9) * 1.1 + rng.uniform(-1.2, 1.2), 1)
        additive = round(18 + (index % 7) * 0.8 + rng.uniform(-0.8, 0.8), 1)
        crosslinker = round(total - resin - additive, 1)
        substrate = ("PET", "PC", "PMMA/PC")[index % 3]
        set_value(sheet, 8, col, f"RESIN-{('A','B','C')[index % 3]}")
        set_value(sheet, 9, col, f"ADD-{('A','B','C')[index % 3]}")
        set_value(sheet, 11, col, round(38 + (index % 8) * .7 + rng.uniform(-.2, .2), 1))
        set_value(sheet, 16, col, f"{owner}-{index:04d}")
        set_value(sheet, 17, col, resin)
        set_value(sheet, 18, col, additive)
        set_value(sheet, 19, col, crosslinker)
        set_value(sheet, 26, col, total)
        set_value(sheet, 27, col, substrate)
        set_value(sheet, 28, col, ("线棒涂布", "刮涂", "旋涂")[index % 3])
        set_value(sheet, 29, col, 650 + (index * 31) % 500)
        set_value(sheet, 30, col, 22 + index % 8)
        set_value(sheet, 31, col, 45 + (index * 3) % 31)
        set_value(sheet, 32, col, round(76 + (index % 12) * 1.8 + rng.uniform(-1, 1), 1))
        set_value(sheet, 33, col, ("F", "H", "2H", "3H")[index % 4])
        set_value(sheet, 34, col, ("5B", "4B", "3B")[index % 3])
        set_value(sheet, 35, col, f"{700 + (index * 17) % 320} 次")
        set_value(sheet, 36, col, f"{400 + (index * 13) % 260} 次")
        if index % 11 == 0:
            set_value(sheet, 35, col, "")
        if index % 17 == 0:
            set_value(sheet, 32, col, "")
    set_value(sheet, 6, 2, ("线棒涂布", "刮涂", "旋涂")[start % 3])
    set_value(sheet, 7, 2, f"UV条件：{650 + (start * 31) % 500} mW/cm²；温度：{22 + start % 8}°C；湿度：{45 + (start * 3) % 31}%RH")
    set_value(sheet, 39 if sheet.max_row < 40 else 40, 1, f"SYNTHETIC_DEMO；来源={owner}；客户模板原生布局。")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--mode", choices=["generate"], default="generate")
    args = parser.parse_args()
    source = args.root / "docs/AI实验优化、配方预测/干净模板表_整理完成/原表优化/应用测试报告模板.xlsx"
    out = args.root / ".runtime/demo/input"
    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("*.xlsx"):
        old.unlink()
    groups = [("data", 240, "DATA_CENTER"), ("experiment", 160, "EXPERIMENT")]
    generated = []
    for kind, count, owner in groups:
        workbook = load_workbook(source)
        index = 1 if kind == "data" else 241
        for sheet in workbook.worksheets:
            fill_sheet(sheet, index, Random(20260917 + index), owner)
        path = out / f"SYNTHETIC_DEMO_{kind}_{count}.xlsx"
        workbook.save(path)
        generated.append(str(path))
    print("\n".join(generated))


if __name__ == "__main__":
    main()
