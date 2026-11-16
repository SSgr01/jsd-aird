# -*- coding: utf-8 -*-
"""把多份"单表工作簿"合并成一个多工作表工作簿。

openpyxl 没有跨工作簿复制工作表的 API，而 `copy_worksheet()` 只能在同一
工作簿内复制当前内容——用它去建第 2、3 张表会把第 1 张表的数据一起搬过去
（见 decisions.md 规则 21）。本模块走 XML 层：以第 1 份为底，把其余工作簿
的工作表 XML 拼进 zip，并重写 workbook.xml / rels / [Content_Types].xml。

用法：
    from merge_sheets import merge_workbooks
    merge_workbooks([p1, p2, p3], out_path)

约定：每份输入工作簿里只保留要交付的那一张工作表（脚本里先删掉其余），
这样"第一张工作表"就是唯一目标。
"""
import os
import re
import zipfile
from xml.sax.saxutils import escape

REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
REL_TYPE = REL_NS + "/worksheet"
CT_PREFIX = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"


def _first_sheet_part(zf, workbook_xml):
    """workbook.xml 里第一张工作表对应的 zip 内路径。"""
    m = re.search(r'<sheet[^>]*r:id="(rId\d+)"', workbook_xml)
    if not m:
        raise ValueError("no <sheet> with r:id in workbook.xml")
    rid = m.group(1)
    rels = zf.read("xl/_rels/workbook.xml.rels").decode("utf-8")
    for tag in re.findall(r"<Relationship\b[^>]*>", rels):
        a_id = re.search(r'\bId="([^"]+)"', tag)
        a_tg = re.search(r'\bTarget="([^"]+)"', tag)
        if a_id and a_tg and a_id.group(1) == rid:
            tgt = a_tg.group(1).lstrip("/")
            return tgt if tgt.startswith("xl/") else "xl/" + tgt
    raise ValueError("relationship target not found for " + rid)


def _print_area(workbook_xml):
    """该工作簿第一张表的打印区域（不含表名前缀），无则 None。"""
    m = re.search(r'<definedName name="_xlnm\.Print_Area" localSheetId="0">([^<]+)</definedName>',
                  workbook_xml)
    return m.group(1).split("!", 1)[1] if m else None


def _print_titles(workbook_xml):
    m = re.search(r'<definedName name="_xlnm\.Print_Titles" localSheetId="0">([^<]+)</definedName>',
                  workbook_xml)
    return m.group(1).split("!", 1)[1] if m else None


def merge_workbooks(paths, out_path):
    """按顺序合并；第 1 份决定样式/定义名称基底。返回工作表名列表。"""
    if not paths:
        raise ValueError("no input workbooks")

    with zipfile.ZipFile(paths[0]) as z:
        base = {n: z.read(n) for n in z.namelist()}

    wb_xml = base["xl/workbook.xml"].decode("utf-8")
    parts = []  # (sheet_xml_bytes, name, print_area, print_titles)
    for p in paths[1:]:
        with zipfile.ZipFile(p) as z:
            w = z.read("xl/workbook.xml").decode("utf-8")
            part = _first_sheet_part(z, w)
            nm = re.search(r'<sheet [^>]*name="([^"]+)"', w).group(1)
            parts.append((z.read(part), nm, _print_area(w), _print_titles(w)))

    used = set(base)
    next_idx = 1
    placed = []
    for xml, nm, area, titles in parts:
        while True:
            cand = "xl/worksheets/sheet%d.xml" % next_idx
            next_idx += 1
            if cand not in used:
                break
        used.add(cand)
        base[cand] = xml
        placed.append((cand, nm, area, titles))

    # 1) <sheet> 节点，sheetId 必须唯一且非零
    sid = max([int(x) for x in re.findall(r'sheetId="(\d+)"', wb_xml)] or [0])
    tags = []
    for cand, nm, area, titles in placed:
        sid += 1
        tags.append('<sheet name="%s" sheetId="%d" r:id="__RID__"/>' % (escape(nm), sid))
    wb_xml = wb_xml.replace("</sheets>", "".join(tags) + "</sheets>")

    # 2) 根节点必须声明 r 命名空间，否则 r:id 无法解析
    if "xmlns:r=" not in wb_xml.split(">", 1)[0]:
        wb_xml = wb_xml.replace("<workbook ", '<workbook xmlns:r="%s" ' % REL_NS, 1)

    # 3) 关系
    rels = base["xl/_rels/workbook.xml.rels"].decode("utf-8")
    mx = max(int(x) for x in re.findall(r'Id="rId(\d+)"', rels))
    new_rels, rids = [], []
    for i, (cand, nm, area, titles) in enumerate(placed):
        rid = "rId%d" % (mx + 1 + i)
        rids.append(rid)
        new_rels.append('<Relationship Id="%s" Type="%s" Target="%s"/>'
                        % (rid, REL_TYPE, cand[len("xl/"):]))
    rels = rels.replace("</Relationships>", "".join(new_rels) + "</Relationships>")
    for rid in rids:
        wb_xml = wb_xml.replace("__RID__", rid, 1)

    # 4) 每张新表各自的打印区域/打印标题，localSheetId 从 1 开始
    dn = ""
    for i, (cand, nm, area, titles) in enumerate(placed):
        q = "'%s'" % nm.replace("'", "''")
        if area:
            dn += ('<definedName name="_xlnm.Print_Area" localSheetId="%d">%s!%s</definedName>'
                   % (i + 1, q, area))
        if titles:
            dn += ('<definedName name="_xlnm.Print_Titles" localSheetId="%d">%s!%s</definedName>'
                   % (i + 1, q, titles))
    if dn:
        if "</definedNames>" in wb_xml:
            wb_xml = wb_xml.replace("</definedNames>", dn + "</definedNames>")
        else:
            wb_xml = wb_xml.replace("</sheets>", "</sheets><definedNames>%s</definedNames>" % dn)

    base["xl/workbook.xml"] = wb_xml.encode("utf-8")
    base["xl/_rels/workbook.xml.rels"] = rels.encode("utf-8")

    # 5) Content_Types
    ct = base["[Content_Types].xml"].decode("utf-8")
    adds = "".join('<Override PartName="/%s" ContentType="%s"/>' % (c, CT_PREFIX)
                   for c, nm, area, titles in placed if ("/" + c) not in ct)
    base["[Content_Types].xml"] = ct.replace("</Types>", adds + "</Types>").encode("utf-8")

    # 6) calcChain 会与新工作表冲突，删掉让 Excel 重建
    if "xl/calcChain.xml" in base:
        del base["xl/calcChain.xml"]
        ct2 = base["[Content_Types].xml"].decode("utf-8")
        ct2 = re.sub(r'<Override PartName="/xl/calcChain\.xml"[^>]*/>', "", ct2)
        base["[Content_Types].xml"] = ct2.encode("utf-8")
        rl2 = base["xl/_rels/workbook.xml.rels"].decode("utf-8")
        rl2 = re.sub(r'<Relationship [^>]*calcChain\.xml"\s*/>', "", rl2)
        base["xl/_rels/workbook.xml.rels"] = rl2.encode("utf-8")

    if os.path.exists(out_path):
        os.remove(out_path)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as zo:
        for n, d in base.items():
            zo.writestr(n, d)

    # 自检：能读回、表数对、每张有打印区域
    from openpyxl import load_workbook
    chk = load_workbook(out_path)
    names = chk.sheetnames
    if len(names) != len(paths):
        raise RuntimeError("sheet count %d != inputs %d" % (len(names), len(paths)))
    for ws in chk.worksheets:
        if not ws.print_area:
            raise RuntimeError("sheet %r lost its print area" % ws.title)
    return names
